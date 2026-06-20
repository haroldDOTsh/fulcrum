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
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
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
import sh.harold.fulcrum.control.lifecycle.ControlLifecycleNames;
import sh.harold.fulcrum.control.lifecycle.LifecyclePhase;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceControlCommand;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceId;
import sh.harold.fulcrum.control.lifecycle.RecordLifecycleObservation;
import sh.harold.fulcrum.control.queue.ControlQueueNames;
import sh.harold.fulcrum.control.queue.FormRosterIntent;
import sh.harold.fulcrum.control.queue.QueueIntentId;
import sh.harold.fulcrum.control.queue.QueuePartitionKey;
import sh.harold.fulcrum.control.queue.QueueRosterCommand;
import sh.harold.fulcrum.control.queue.QueueRosterControlCommand;
import sh.harold.fulcrum.control.queue.RosterIntentId;
import sh.harold.fulcrum.control.queue.SubmitQueueIntent;
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
        requireTopicProduce(settings.queueRosterCommandTopic());
        requireTopicProduce(settings.sharedShardPlacementCommandTopic());
        requireTopicProduce(settings.routeCommandTopic());
        requireTopicProduce(settings.routeAttemptCommandTopic());
        requireTopicProduce(settings.lifecycleTraceCommandTopic());
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
        Optional<RoutePlan> routePlan = routePlan(suffix, routeAttemptSuffix(suffix, attemptedAt), placementDecision, attemptedAt);
        if (routePlan.isEmpty()) {
            publishPlacementRequest(placementRequest, candidates);
            producer.flush();
            return VelocityLoginGateDecision.denied(request.subjectId(), NO_LOBBY_ROUTE_REASON);
        }

        RoutePlan selectedRoute = routePlan.orElseThrow();
        publishQueueRosterSequence(request, suffix, trace);
        publishPresenceClaim(request, suffix, trace, selectedRoute);
        publishPlacementRequest(placementRequest, candidates);
        publishRouteOpen(request, trace, selectedRoute);
        publishRouteAttemptSequence(request, trace, selectedRoute);
        publishLifecycleTraceSequence(request, suffix, trace, selectedRoute);
        producer.flush();
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
            String routeSuffix,
            String routeAttemptSuffix,
            SharedShardPlacementDecision placementDecision,
            Instant requestedAt) {
        if (placementDecision.status() != SharedShardPlacementDecisionStatus.SELECTED_EXISTING_SESSION) {
            return Optional.empty();
        }
        return Optional.of(new RoutePlan(
                new RouteId("route-velocity-login-" + routeSuffix),
                new RouteAttemptId("route-attempt-velocity-login-" + routeAttemptSuffix),
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

    private void publishLifecycleTraceSequence(
            VelocityLoginGateRequest request,
            String subjectSuffix,
            TraceEnvelope trace,
            RoutePlan routePlan) {
        Instant submittedAt = request.attemptedAt();
        sendLifecycleTrace(lifecycleTraceCommand(
                trace,
                LifecyclePhase.QUEUE_INTENT_SUBMITTED,
                "queue-intent",
                "queue-intent-velocity-login-" + subjectSuffix,
                Optional.empty(),
                Optional.empty(),
                "queue",
                submittedAt));
        sendLifecycleTrace(lifecycleTraceCommand(
                trace,
                LifecyclePhase.ROSTER_INTENT_FORMED,
                "roster-intent",
                "roster-intent-velocity-login-" + subjectSuffix,
                Optional.empty(),
                Optional.empty(),
                "roster",
                submittedAt.plusMillis(1)));
        sendLifecycleTrace(lifecycleTraceCommand(
                trace,
                LifecyclePhase.ALLOCATION_CLAIMED,
                "slot",
                routePlan.slotId().value(),
                Optional.of(routePlan.sessionId()),
                Optional.of(settings.lobbyResolvedManifestId()),
                "allocation",
                routePlan.requestedAt()));
        sendLifecycleTrace(lifecycleTraceCommand(
                trace,
                LifecyclePhase.ROUTE_ATTEMPT_CREATED,
                "route-attempt",
                routePlan.routeAttemptId().value(),
                Optional.of(routePlan.sessionId()),
                Optional.of(settings.lobbyResolvedManifestId()),
                "route-attempt",
                routePlan.requestedAt()));
    }

    private LifecycleTraceControlCommand<RecordLifecycleObservation> lifecycleTraceCommand(
            TraceEnvelope trace,
            LifecyclePhase phase,
            String aggregateType,
            String aggregateId,
            Optional<SessionId> sessionId,
            Optional<ResolvedManifestId> resolvedManifestId,
            String commandSuffix,
            Instant observedAt) {
        LifecycleTraceId traceId = new LifecycleTraceId(trace.traceId());
        TraceEnvelope commandTrace = trace.child("span-lifecycle-" + commandSuffix + "-" + compact(traceId), observedAt);
        RecordLifecycleObservation payload = new RecordLifecycleObservation(
                traceId,
                phase,
                aggregateType,
                aggregateId,
                sessionId,
                resolvedManifestId,
                observedAt,
                commandTrace);
        PrincipalId principal = securityContext.identity().principalId();
        return new LifecycleTraceControlCommand<>(
                new CommandEnvelope<>(
                        new CommandId("command-lifecycle-velocity-login-" + commandSuffix + "-" + compact(traceId)),
                        new IdempotencyKey("idem-lifecycle-velocity-login-" + commandSuffix + "-" + compact(traceId)),
                        principal,
                        ControlLifecycleNames.traceAggregateId(traceId),
                        ControlLifecycleNames.TRACE_CONTRACT,
                        ControlLifecycleNames.RECORD_LIFECYCLE_OBSERVATION,
                        commandTrace,
                        Optional.of(observedAt.plus(ROUTE_DEADLINE)),
                        payload),
                principal,
                1,
                Optional.empty(),
                "lifecycle-trace|phase=" + phase.name()
                        + "|traceId=" + traceId.value()
                        + "|aggregateType=" + aggregateType
                        + "|aggregateId=" + aggregateId,
                observedAt);
    }

    private void sendLifecycleTrace(LifecycleTraceControlCommand<RecordLifecycleObservation> command) {
        send(
                settings.lifecycleTraceCommandTopic(),
                ControlLifecycleNames.traceAggregateId(command.envelope().payload().traceId()).value(),
                ControlCommandWireCodec.encodeLifecycleTraceRecord(command));
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

    private void publishQueueRosterSequence(
            VelocityLoginGateRequest request,
            String suffix,
            TraceEnvelope trace) {
        QueueIntentId queueIntentId = new QueueIntentId("queue-intent-velocity-login-" + suffix);
        RosterIntentId rosterIntentId = new RosterIntentId("roster-intent-velocity-login-" + suffix);
        QueuePartitionKey partitionKey =
                new QueuePartitionKey(settings.lobbyExperienceId(), Optional.empty(), settings.lobbyPoolId());
        Instant submittedAt = request.attemptedAt();
        Instant formedAt = submittedAt.plusMillis(1);
        sendQueueRoster(queueRosterCommand(
                new SubmitQueueIntent(
                        queueIntentId,
                        List.of(request.subjectId()),
                        settings.lobbyExperienceId(),
                        Optional.empty(),
                        settings.lobbyPoolId(),
                        0,
                        submittedAt,
                        submittedAt.plus(ROUTE_DEADLINE),
                        trace.child("span-queue-submit-velocity-login-" + suffix, submittedAt)),
                ControlQueueNames.SUBMIT_QUEUE_INTENT,
                "submit",
                0,
                submittedAt));
        sendQueueRoster(queueRosterCommand(
                new FormRosterIntent(
                        rosterIntentId,
                        partitionKey,
                        List.of(queueIntentId),
                        1,
                        formedAt,
                        trace.child("span-roster-form-velocity-login-" + suffix, formedAt)),
                ControlQueueNames.FORM_ROSTER_INTENT,
                "form",
                1,
                formedAt));
    }

    private <T extends QueueRosterCommand> QueueRosterControlCommand<T> queueRosterCommand(
            T payload,
            CommandName commandName,
            String commandSuffix,
            long expectedRevision,
            Instant receivedAt) {
        String suffix = compact(payload);
        PrincipalId principal = securityContext.identity().principalId();
        return new QueueRosterControlCommand<>(
                new CommandEnvelope<>(
                        new CommandId("command-queue-velocity-login-" + commandSuffix + "-" + suffix),
                        new IdempotencyKey("idem-queue-velocity-login-" + commandSuffix + "-" + suffix),
                        principal,
                        ControlQueueNames.aggregateId(payload.partitionKey()),
                        ControlQueueNames.CONTRACT,
                        commandName,
                        new TraceEnvelope(
                                "trace-queue-velocity-login-" + suffix,
                                "span-queue-velocity-login-" + commandSuffix + "-" + suffix,
                                Optional.empty(),
                                receivedAt,
                                "velocity-login-routing",
                                securityContext.identity().instanceId()),
                        Optional.of(receivedAt.plus(ROUTE_DEADLINE)),
                        payload),
                principal,
                1,
                Optional.of(new Revision(expectedRevision)),
                "queue-roster|command=" + commandName.value()
                        + "|id=" + suffix
                        + "|revision=" + expectedRevision,
                receivedAt);
    }

    private void sendQueueRoster(QueueRosterControlCommand<? extends QueueRosterCommand> command) {
        send(
                settings.queueRosterCommandTopic(),
                ControlQueueNames.aggregateId(command.envelope().payload().partitionKey()).value(),
                ControlCommandWireCodec.encodeQueueRosterCommand(command));
    }

    private static String compact(SubjectId subjectId) {
        return subjectId.value().toString().replace("-", "");
    }

    private static String routeAttemptSuffix(String subjectSuffix, Instant attemptedAt) {
        return subjectSuffix + "-" + attemptedAt.getEpochSecond() + "n" + attemptedAt.getNano();
    }

    private static String compact(RouteAttemptId routeAttemptId) {
        return routeAttemptId.value().replace("-", "");
    }

    private static String compact(LifecycleTraceId traceId) {
        return traceId.value().replace("-", "");
    }

    private static String compact(QueueRosterCommand payload) {
        if (payload instanceof SubmitQueueIntent submit) {
            return submit.queueIntentId().value().replace("-", "");
        }
        if (payload instanceof FormRosterIntent form) {
            return form.rosterIntentId().value().replace("-", "");
        }
        throw new IllegalArgumentException("Unsupported queue-roster payload " + payload.getClass().getSimpleName());
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
