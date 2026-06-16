package sh.harold.fulcrum.control.route;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RouteControllerChaosSuiteTest {
    private static final RouteAttemptId ROUTE_ATTEMPT_ID = new RouteAttemptId("route-attempt-chaos-1");
    private static final PrincipalId PRINCIPAL_ID = new PrincipalId("principal-controller-route-chaos");
    private static final Instant BASE_TIME = Instant.parse("2026-06-16T23:00:00Z");
    private static final long FENCING_EPOCH = 31;

    @Test
    void routeRetryResumesFromReplayedStateAfterControllerRestart() {
        RouteAttemptController controller = new RouteAttemptController();
        RouteAttemptControlRecord record = RouteAttemptController.emptyRecord(FENCING_EPOCH);
        List<RouteAttemptEvent> events = new ArrayList<>();

        record = accept(controller, record, request(), ControlRouteNames.REQUEST_ROUTE_ATTEMPT, "cmd-request", BASE_TIME, events).record();
        record = accept(controller, record, new IssueProxyRoute(ROUTE_ATTEMPT_ID, BASE_TIME.plusSeconds(1)),
                ControlRouteNames.ISSUE_PROXY_ROUTE, "cmd-proxy", BASE_TIME.plusSeconds(1), events).record();
        record = accept(controller, record, new TimeoutRouteAttempt(ROUTE_ATTEMPT_ID, BASE_TIME.plusSeconds(31)),
                ControlRouteNames.TIMEOUT_ROUTE_ATTEMPT, "cmd-timeout", BASE_TIME.plusSeconds(31), events).record();

        RouteAttemptControlRecord replayed = RouteAttemptController.replay(FENCING_EPOCH, events);
        RouteAttemptController restartedController = new RouteAttemptController();

        RouteAttemptDecision retry = accept(
                restartedController,
                replayed,
                new RetryRouteAttempt(ROUTE_ATTEMPT_ID, BASE_TIME.plusSeconds(32), BASE_TIME.plusSeconds(90)),
                ControlRouteNames.RETRY_ROUTE_ATTEMPT,
                "cmd-retry",
                BASE_TIME.plusSeconds(32),
                events);

        RouteAttemptSnapshot snapshot = retry.record().snapshot().orElseThrow();
        assertEquals(RouteAttemptLifecycleStatus.CREATED, snapshot.status());
        assertEquals(1, snapshot.retryCount());
        assertEquals(BASE_TIME.plusSeconds(90), snapshot.deadlineAt());
        assertEquals(new Revision(4), retry.revision());
    }

    @Test
    void duplicateHostAttachAfterRestartDoesNotAdvanceRouteOrEmitCommands() {
        RouteAttemptController controller = new RouteAttemptController();
        RouteAttemptControlRecord record = RouteAttemptController.emptyRecord(FENCING_EPOCH);
        List<RouteAttemptEvent> events = new ArrayList<>();

        record = accept(controller, record, request(), ControlRouteNames.REQUEST_ROUTE_ATTEMPT, "cmd-request", BASE_TIME, events).record();
        record = accept(controller, record, new IssueProxyRoute(ROUTE_ATTEMPT_ID, BASE_TIME.plusSeconds(1)),
                ControlRouteNames.ISSUE_PROXY_ROUTE, "cmd-proxy", BASE_TIME.plusSeconds(1), events).record();
        record = accept(controller, record, new PrepareHostRoute(ROUTE_ATTEMPT_ID, BASE_TIME.plusSeconds(2)),
                ControlRouteNames.PREPARE_HOST_ROUTE, "cmd-host", BASE_TIME.plusSeconds(2), events).record();
        record = accept(controller, record, new ObserveHostAttach(ROUTE_ATTEMPT_ID, BASE_TIME.plusSeconds(3)),
                ControlRouteNames.OBSERVE_HOST_ATTACH, "cmd-attach", BASE_TIME.plusSeconds(3), events).record();

        RouteAttemptControlRecord replayed = RouteAttemptController.replay(FENCING_EPOCH, events);
        RouteAttemptController restartedController = new RouteAttemptController();

        RouteAttemptDecision duplicateAttach = restartedController.handle(command(
                new ObserveHostAttach(ROUTE_ATTEMPT_ID, BASE_TIME.plusSeconds(4)),
                ControlRouteNames.OBSERVE_HOST_ATTACH,
                "cmd-attach-duplicate",
                "idem-attach-duplicate",
                BASE_TIME.plusSeconds(4),
                Optional.of(replayed.revision()),
                PRINCIPAL_ID,
                FENCING_EPOCH), replayed);
        RouteAttemptDecision ack = accept(
                restartedController,
                replayed,
                new AcknowledgeRouteAttempt(ROUTE_ATTEMPT_ID, BASE_TIME.plusSeconds(5)),
                ControlRouteNames.ACKNOWLEDGE_ROUTE_ATTEMPT,
                "cmd-ack",
                BASE_TIME.plusSeconds(5),
                events);

        assertEquals(RouteAttemptDecisionStatus.REJECTED, duplicateAttach.status());
        assertEquals(Optional.of(RouteAttemptRejectionReason.INVALID_TRANSITION), duplicateAttach.receipt().rejectionReason());
        assertTrue(duplicateAttach.events().isEmpty());
        assertTrue(duplicateAttach.emissions().isEmpty());
        assertEquals(replayed.revision(), duplicateAttach.revision());
        assertEquals(RouteAttemptDecisionStatus.ACCEPTED, ack.status());
        assertEquals(RouteAttemptLifecycleStatus.ACKED, ack.record().snapshot().orElseThrow().status());
        assertEquals(new Revision(5), ack.revision());
    }

    @Test
    void staleControllerOwnerCannotMutateAfterFencingHandoff() {
        RouteAttemptController controller = new RouteAttemptController();
        RouteAttemptControlRecord record = RouteAttemptController.emptyRecord(FENCING_EPOCH);

        RouteAttemptDecision staleOwner = controller.handle(command(
                request(),
                ControlRouteNames.REQUEST_ROUTE_ATTEMPT,
                "cmd-request",
                "idem-request",
                BASE_TIME,
                Optional.of(record.revision()),
                PRINCIPAL_ID,
                FENCING_EPOCH - 1), record);

        assertEquals(RouteAttemptDecisionStatus.REJECTED, staleOwner.status());
        assertEquals(Optional.of(RouteAttemptRejectionReason.STALE_FENCING_EPOCH), staleOwner.receipt().rejectionReason());
        assertTrue(record.snapshot().isEmpty());
    }

    private static RouteAttemptDecision accept(
            RouteAttemptController controller,
            RouteAttemptControlRecord record,
            RouteAttemptCommand payload,
            CommandName commandName,
            String commandId,
            Instant receivedAt,
            List<RouteAttemptEvent> events) {
        RouteAttemptDecision decision = controller.handle(command(
                payload,
                commandName,
                commandId,
                "idem-" + commandId,
                receivedAt,
                Optional.of(record.revision()),
                PRINCIPAL_ID,
                FENCING_EPOCH), record);
        assertEquals(RouteAttemptDecisionStatus.ACCEPTED, decision.status());
        events.addAll(decision.events());
        return decision;
    }

    private static <T extends RouteAttemptCommand> RouteAttemptControlCommand<T> command(
            T payload,
            CommandName commandName,
            String commandId,
            String idempotencyKey,
            Instant receivedAt,
            Optional<Revision> expectedRevision,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch) {
        return new RouteAttemptControlCommand<>(
                new CommandEnvelope<>(
                        new CommandId(commandId),
                        new IdempotencyKey(idempotencyKey),
                        PRINCIPAL_ID,
                        aggregateId(payload),
                        ControlRouteNames.CONTRACT,
                        commandName,
                        trace(),
                        Optional.of(receivedAt.plusSeconds(30)),
                        payload),
                authenticatedPrincipal,
                fencingEpoch,
                expectedRevision,
                payload.getClass().getSimpleName() + ":" + commandId,
                receivedAt);
    }

    private static AggregateId aggregateId(RouteAttemptCommand payload) {
        return ControlRouteNames.aggregateId(payload.routeAttemptId());
    }

    private static RequestRouteAttempt request() {
        return new RequestRouteAttempt(
                ROUTE_ATTEMPT_ID,
                new RouteId("route-chaos-1"),
                new SessionId("session-route-chaos-1"),
                new SlotId("slot-agones-chaos-1"),
                List.of(new SubjectId(UUID.fromString("00000000-0000-0000-0000-000000000901"))),
                List.of(new InstanceId("instance-velocity-chaos-1")),
                new PresenceId("presence-chaos-1"),
                new InstanceId("instance-paper-chaos-1"),
                new ResolvedManifestId("manifest-chaos-1"),
                BASE_TIME,
                BASE_TIME.plusSeconds(30),
                trace());
    }

    private static TraceEnvelope trace() {
        return new TraceEnvelope(
                "trace-route-chaos",
                "span-route-chaos",
                Optional.empty(),
                BASE_TIME,
                "route-controller-chaos-suite",
                new InstanceId("instance-controller-route-chaos"));
    }
}
