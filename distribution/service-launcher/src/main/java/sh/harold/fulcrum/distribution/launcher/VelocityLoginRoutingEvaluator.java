package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.PresenceId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.control.instance.ExperienceShape;
import sh.harold.fulcrum.control.instance.SharedShardExperienceDescriptor;
import sh.harold.fulcrum.control.instance.SharedShardPlacementCandidate;
import sh.harold.fulcrum.control.instance.SharedShardPlacementController;
import sh.harold.fulcrum.control.instance.SharedShardPlacementDecision;
import sh.harold.fulcrum.control.instance.SharedShardPlacementDecisionStatus;
import sh.harold.fulcrum.control.instance.SharedShardPlacementRequest;
import sh.harold.fulcrum.control.instance.SharedShardPoolDescriptor;
import sh.harold.fulcrum.control.route.ControlRouteNames;
import sh.harold.fulcrum.control.route.IssueProxyRoute;
import sh.harold.fulcrum.control.route.PrepareHostRoute;
import sh.harold.fulcrum.control.route.RequestRouteAttempt;
import sh.harold.fulcrum.control.route.RouteAttemptCommand;
import sh.harold.fulcrum.control.route.RouteAttemptControlCommand;
import sh.harold.fulcrum.control.route.RouteAttemptId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.presence.ClaimPresence;
import sh.harold.fulcrum.data.presence.PresenceAuthority;
import sh.harold.fulcrum.data.presence.PresenceCommand;
import sh.harold.fulcrum.data.presence.PresenceOwnerToken;
import sh.harold.fulcrum.data.route.contract.OpenRoute;
import sh.harold.fulcrum.data.route.contract.RouteCommand;
import sh.harold.fulcrum.data.route.contract.RouteContracts;
import sh.harold.fulcrum.host.api.HostAccessMode;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostResourceFamily;
import sh.harold.fulcrum.host.api.HostSecurityContext;
import sh.harold.fulcrum.host.velocity.VelocityLoginGateDecision;
import sh.harold.fulcrum.host.velocity.VelocityLoginGateEvaluator;
import sh.harold.fulcrum.host.velocity.VelocityLoginGateRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class VelocityLoginRoutingEvaluator implements VelocityLoginGateEvaluator {
    private static final Duration ROUTE_DEADLINE = Duration.ofSeconds(30);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(30);
    static final String NO_LOBBY_ROUTE_REASON = "No lobby route is currently available";

    private final VelocityLoginGateEvaluator delegate;
    private final Producer<String, String> producer;
    private final HostSecurityContext securityContext;
    private final RuntimeConnectionSettings.VelocityConnections settings;
    private final VelocitySharedShardAllocationRegistry allocations;
    private final SharedShardPlacementController placementController = new SharedShardPlacementController();

    VelocityLoginRoutingEvaluator(
            VelocityLoginGateEvaluator delegate,
            Producer<String, String> producer,
            HostSecurityContext securityContext,
            RuntimeConnectionSettings.VelocityConnections settings,
            VelocitySharedShardAllocationRegistry allocations) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.producer = Objects.requireNonNull(producer, "producer");
        this.securityContext = Objects.requireNonNull(securityContext, "securityContext");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.allocations = Objects.requireNonNull(allocations, "allocations");
        if (!HostInstanceKinds.VELOCITY.equals(securityContext.identity().instanceKind())) {
            throw new IllegalArgumentException("Velocity login routing requires a Velocity Instance identity");
        }
        requireTopicProduce(settings.presenceCommandTopic());
        requireTopicProduce(settings.sharedShardPlacementCommandTopic());
        requireTopicProduce(settings.routeCommandTopic());
        requireTopicProduce(settings.routeAttemptCommandTopic());
    }

    @Override
    public VelocityLoginGateDecision evaluate(VelocityLoginGateRequest request) {
        Objects.requireNonNull(request, "request");
        VelocityLoginGateDecision decision = delegate.evaluate(request);
        if (decision.allowed()) {
            return publishLoginIntents(request);
        }
        return decision;
    }

    private VelocityLoginGateDecision publishLoginIntents(VelocityLoginGateRequest request) {
        String suffix = compact(request.subjectId());
        Instant attemptedAt = request.attemptedAt();
        TraceEnvelope trace = new TraceEnvelope(
                "trace-velocity-login-" + suffix,
                "span-velocity-login-" + suffix,
                Optional.empty(),
                attemptedAt,
                "velocity-login-routing",
                securityContext.identity().instanceId());
        List<SharedShardPlacementCandidate> candidates =
                allocations.placementCandidates(settings, trace, attemptedAt);
        SharedShardPlacementRequest placementRequest = placementRequest(request, suffix, trace);
        SharedShardPlacementDecision placementDecision =
                placementController.place(placementRequest, candidates);
        Optional<RoutePlan> routePlan = routePlan(suffix, placementDecision, attemptedAt);
        if (routePlan.isEmpty()) {
            publishPlacementRequest(placementRequest, candidates);
            producer.flush();
            return VelocityLoginGateDecision.denied(request.subjectId(), NO_LOBBY_ROUTE_REASON);
        }

        RoutePlan selectedRoute = routePlan.orElseThrow();
        PresenceId routedPresenceId = presenceId(suffix);
        allocations.recordRoutedPresence(selectedRoute.sessionId(), routedPresenceId);
        try {
            publishPresenceClaim(request, suffix, trace, selectedRoute);
            publishPlacementRequest(placementRequest, candidates);
            publishRouteOpen(request, trace, selectedRoute);
            publishRouteAttemptSequence(request, trace, selectedRoute);
            producer.flush();
        } catch (RuntimeException exception) {
            allocations.removeRoutedPresence(selectedRoute.sessionId(), routedPresenceId);
            throw exception;
        }
        return VelocityLoginGateDecision.allowed(request.subjectId());
    }

    private SharedShardPlacementRequest placementRequest(
            VelocityLoginGateRequest request,
            String suffix,
            TraceEnvelope trace) {
        return new SharedShardPlacementRequest(
                new SharedShardExperienceDescriptor(
                        settings.lobbyExperienceId(),
                        ExperienceShape.SHARED_SHARD,
                        new SharedShardPoolDescriptor(
                                settings.lobbyPoolId(),
                                settings.lobbyAgonesFleetName(),
                                settings.lobbyTargetCapacity(),
                                settings.lobbyHardCapacity()),
                        settings.lobbyResolvedManifestId()),
                request.subjectId(),
                presenceId(suffix),
                "placement-velocity-login-" + suffix,
                Optional.of(settings.lobbyCapabilityScopeFingerprint()),
                request.attemptedAt(),
                trace);
    }

    private Optional<RoutePlan> routePlan(
            String suffix,
            SharedShardPlacementDecision placementDecision,
            Instant requestedAt) {
        if (placementDecision.status() != SharedShardPlacementDecisionStatus.SELECTED_EXISTING_SESSION) {
            return Optional.empty();
        }
        return Optional.of(new RoutePlan(
                new RouteId("route-velocity-login-" + suffix),
                new RouteAttemptId("route-attempt-velocity-login-" + suffix),
                placementDecision.sessionId().orElseThrow(),
                placementDecision.slotId().orElseThrow(),
                placementDecision.instanceId().orElseThrow(),
                requestedAt));
    }

    private void publishPresenceClaim(
            VelocityLoginGateRequest request,
            String suffix,
            TraceEnvelope trace,
            RoutePlan routePlan) {
        Instant observedAt = request.attemptedAt();
        ClaimPresence claim = new ClaimPresence(
                presenceId(suffix),
                request.subjectId(),
                securityContext.identity().instanceId(),
                new PresenceOwnerToken("owner-token-velocity-login-" + suffix),
                Optional.of(routePlan.sessionId()),
                Optional.of(routePlan.routeId()),
                observedAt,
                observedAt.plus(settings.presenceLease()));
        CommandEnvelope<PresenceCommand> envelope = new CommandEnvelope<>(
                new CommandId("command-presence-velocity-login-" + suffix),
                new IdempotencyKey("idem-presence-velocity-login-" + suffix),
                securityContext.identity().principalId(),
                PresenceAuthority.aggregateId(request.subjectId()),
                new ContractName(PresenceAuthorityWireCodec.CONTRACT),
                new CommandName(PresenceAuthorityWireCodec.CLAIM_COMMAND),
                trace.child("span-presence-velocity-login-" + suffix, observedAt),
                Optional.of(observedAt.plus(settings.presenceLease())),
                claim);
        AuthorityCommand<PresenceCommand> command = new AuthorityCommand<>(
                envelope,
                securityContext.identity().principalId(),
                1,
                Optional.of(new Revision(0)),
                "claim-presence|subjectId=" + request.subjectId().value()
                        + "|presenceId=" + claim.presenceId().value()
                        + "|sessionId=" + claim.sessionId().map(SessionId::value).orElse("none")
                        + "|routeId=" + claim.routeId().map(RouteId::value).orElse("none"),
                observedAt);
        send(settings.presenceCommandTopic(), envelope.aggregateId().value(), PresenceAuthorityWireCodec.encodeCommand(command));
    }

    private void publishPlacementRequest(
            SharedShardPlacementRequest request,
            List<SharedShardPlacementCandidate> candidates) {
        send(
                settings.sharedShardPlacementCommandTopic(),
                request.placementAttemptId(),
                ControlCommandWireCodec.encodeSharedShardPlacementRequest(request, candidates));
    }

    private void publishRouteOpen(
            VelocityLoginGateRequest request,
            TraceEnvelope trace,
            RoutePlan routePlan) {
        Instant requestedAt = routePlan.requestedAt();
        OpenRoute open = new OpenRoute(
                routePlan.routeId(),
                request.subjectId(),
                routePlan.sessionId(),
                routePlan.targetInstanceId(),
                requestedAt,
                requestedAt.plus(ROUTE_DEADLINE));
        CommandEnvelope<RouteCommand> envelope = new CommandEnvelope<>(
                new CommandId("command-route-open-velocity-login-" + compact(request.subjectId())),
                new IdempotencyKey("idem-route-open-velocity-login-" + compact(request.subjectId())),
                securityContext.identity().principalId(),
                RouteContracts.aggregateId(routePlan.routeId()),
                RouteContracts.CONTRACT_NAME,
                RouteContracts.commandName(open),
                trace.child("span-route-open-velocity-login-" + compact(request.subjectId()), requestedAt),
                Optional.of(requestedAt.plus(ROUTE_DEADLINE)),
                open);
        AuthorityCommand<RouteCommand> command = new AuthorityCommand<>(
                envelope,
                securityContext.identity().principalId(),
                1,
                Optional.of(new Revision(0)),
                "open-route|routeId=" + routePlan.routeId().value()
                        + "|subjectId=" + request.subjectId().value()
                        + "|sessionId=" + routePlan.sessionId().value()
                        + "|targetInstanceId=" + routePlan.targetInstanceId().value(),
                requestedAt);
        send(settings.routeCommandTopic(), envelope.aggregateId().value(), RouteAuthorityWireCodec.encodeCommand(command));
    }

    private void publishRouteAttemptSequence(
            VelocityLoginGateRequest request,
            TraceEnvelope trace,
            RoutePlan routePlan) {
        sendRouteAttempt(routeAttemptCommand(
                new RequestRouteAttempt(
                        routePlan.routeAttemptId(),
                        routePlan.routeId(),
                        routePlan.sessionId(),
                        routePlan.slotId(),
                        List.of(request.subjectId()),
                        List.of(securityContext.identity().instanceId()),
                        presenceId(compact(request.subjectId())),
                        routePlan.targetInstanceId(),
                        settings.lobbyResolvedManifestId(),
                        routePlan.requestedAt(),
                        routePlan.requestedAt().plus(ROUTE_DEADLINE),
                        trace.child("span-route-request-velocity-login-" + compact(request.subjectId()), routePlan.requestedAt())),
                ControlRouteNames.REQUEST_ROUTE_ATTEMPT,
                "request",
                0,
                routePlan.requestedAt()));
        sendRouteAttempt(routeAttemptCommand(
                new IssueProxyRoute(routePlan.routeAttemptId(), routePlan.requestedAt().plusSeconds(1)),
                ControlRouteNames.ISSUE_PROXY_ROUTE,
                "issue-proxy",
                1,
                routePlan.requestedAt().plusSeconds(1)));
        sendRouteAttempt(routeAttemptCommand(
                new PrepareHostRoute(routePlan.routeAttemptId(), routePlan.requestedAt().plusSeconds(2)),
                ControlRouteNames.PREPARE_HOST_ROUTE,
                "prepare-host",
                2,
                routePlan.requestedAt().plusSeconds(2)));
    }

    private <T extends RouteAttemptCommand> RouteAttemptControlCommand<T> routeAttemptCommand(
            T payload,
            CommandName commandName,
            String commandSuffix,
            long expectedRevision,
            Instant receivedAt) {
        String suffix = compact(payload.routeAttemptId());
        PrincipalId principal = securityContext.identity().principalId();
        return new RouteAttemptControlCommand<>(
                new CommandEnvelope<>(
                        new CommandId("command-route-velocity-login-" + commandSuffix + "-" + suffix),
                        new IdempotencyKey("idem-route-velocity-login-" + commandSuffix + "-" + suffix),
                        principal,
                        ControlRouteNames.aggregateId(payload.routeAttemptId()),
                        ControlRouteNames.CONTRACT,
                        commandName,
                        new TraceEnvelope(
                                "trace-route-velocity-login-" + suffix,
                                "span-route-velocity-login-" + commandSuffix + "-" + suffix,
                                Optional.empty(),
                                receivedAt,
                                "velocity-login-routing",
                                securityContext.identity().instanceId()),
                        Optional.of(receivedAt.plus(ROUTE_DEADLINE)),
                        payload),
                principal,
                1,
                Optional.of(new Revision(expectedRevision)),
                "route-attempt|command=" + commandName.value()
                        + "|routeAttemptId=" + payload.routeAttemptId().value()
                        + "|revision=" + expectedRevision,
                receivedAt);
    }

    private void sendRouteAttempt(RouteAttemptControlCommand<? extends RouteAttemptCommand> command) {
        send(
                settings.routeAttemptCommandTopic(),
                ControlRouteNames.aggregateId(command.envelope().payload().routeAttemptId()).value(),
                ControlCommandWireCodec.encodeRouteAttemptCommand(command));
    }

    private void send(String topic, String key, String value) {
        try {
            producer.send(new ProducerRecord<>(topic, key, value))
                    .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing Velocity login intent", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Failed to publish Velocity login intent to " + topic, exception);
        }
    }

    private void requireTopicProduce(String topic) {
        if (!securityContext.credentialScope().permits(HostResourceFamily.TOPIC, HostAccessMode.PRODUCE, topic)) {
            throw new SecurityException("Velocity Instance is not allowed to produce " + topic);
        }
    }

    private static PresenceId presenceId(String suffix) {
        return new PresenceId("presence-velocity-login-" + suffix);
    }

    private static String compact(SubjectId subjectId) {
        return subjectId.value().toString().replace("-", "");
    }

    private static String compact(RouteAttemptId routeAttemptId) {
        return routeAttemptId.value().replace("-", "");
    }

    private record RoutePlan(
            RouteId routeId,
            RouteAttemptId routeAttemptId,
            SessionId sessionId,
            SlotId slotId,
            InstanceId targetInstanceId,
            Instant requestedAt) {
        private RoutePlan {
            routeId = Objects.requireNonNull(routeId, "routeId");
            routeAttemptId = Objects.requireNonNull(routeAttemptId, "routeAttemptId");
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            slotId = Objects.requireNonNull(slotId, "slotId");
            targetInstanceId = Objects.requireNonNull(targetInstanceId, "targetInstanceId");
            requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
        }
    }
}
