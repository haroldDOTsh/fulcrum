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

final class RouteAttemptControllerTest {
    private static final RouteAttemptId ROUTE_ATTEMPT_ID = new RouteAttemptId("route-attempt-1");
    private static final PrincipalId PRINCIPAL_ID = new PrincipalId("principal-controller-route");
    private static final Instant BASE_TIME = Instant.parse("2026-06-16T12:00:00Z");

    @Test
    void routeAttemptEmitsProxyAndHostCommandsAndReplaysFromEvents() {
        RouteAttemptController controller = new RouteAttemptController();
        RouteAttemptControlRecord record = RouteAttemptController.emptyRecord(3);
        List<RouteAttemptEvent> events = new ArrayList<>();

        record = accept(controller, record, request(), ControlRouteNames.REQUEST_ROUTE_ATTEMPT, "cmd-request", BASE_TIME, events).record();
        RouteAttemptDecision proxyDecision = accept(controller, record, new IssueProxyRoute(ROUTE_ATTEMPT_ID, BASE_TIME.plusSeconds(1)),
                ControlRouteNames.ISSUE_PROXY_ROUTE, "cmd-proxy", BASE_TIME.plusSeconds(1), events);
        record = proxyDecision.record();
        assertTrue(proxyDecision.emissions().stream().anyMatch(emission -> emission.kind() == RouteAttemptControlEmissionKind.PROXY_COMMAND));

        RouteAttemptDecision hostDecision = accept(controller, record, new PrepareHostRoute(ROUTE_ATTEMPT_ID, BASE_TIME.plusSeconds(2)),
                ControlRouteNames.PREPARE_HOST_ROUTE, "cmd-host", BASE_TIME.plusSeconds(2), events);
        record = hostDecision.record();
        assertTrue(hostDecision.emissions().stream().anyMatch(emission -> emission.kind() == RouteAttemptControlEmissionKind.HOST_COMMAND));

        record = accept(controller, record, new ObserveHostAttach(ROUTE_ATTEMPT_ID, BASE_TIME.plusSeconds(3)),
                ControlRouteNames.OBSERVE_HOST_ATTACH, "cmd-attach", BASE_TIME.plusSeconds(3), events).record();
        record = accept(controller, record, new AcknowledgeRouteAttempt(ROUTE_ATTEMPT_ID, BASE_TIME.plusSeconds(4)),
                ControlRouteNames.ACKNOWLEDGE_ROUTE_ATTEMPT, "cmd-ack", BASE_TIME.plusSeconds(4), events).record();

        RouteAttemptSnapshot snapshot = record.snapshot().orElseThrow();
        assertEquals(RouteAttemptLifecycleStatus.ACKED, snapshot.status());
        assertEquals(new Revision(5), record.revision());
        assertEquals(record, RouteAttemptController.replay(3, events));
    }

    @Test
    void duplicateCommandReplaysStoredDecisionWithoutNewEvents() {
        RouteAttemptController controller = new RouteAttemptController();
        RouteAttemptControlRecord record = RouteAttemptController.emptyRecord(3);
        RouteAttemptControlCommand<RequestRouteAttempt> command = command(
                request(),
                ControlRouteNames.REQUEST_ROUTE_ATTEMPT,
                "cmd-request",
                "idem-request",
                BASE_TIME,
                Optional.empty(),
                PRINCIPAL_ID);

        RouteAttemptDecision first = controller.handle(command, record);
        RouteAttemptDecision second = controller.handle(command, first.record());

        assertEquals(RouteAttemptDecisionStatus.ACCEPTED, first.status());
        assertEquals(RouteAttemptDecisionStatus.REPLAYED, second.status());
        assertEquals(first.receipt(), second.receipt());
        assertTrue(second.events().isEmpty());
        assertTrue(second.emissions().isEmpty());
    }

    @Test
    void timeoutAndRetryMoveAttemptBackToCreatedWithAdvancedDeadline() {
        RouteAttemptController controller = new RouteAttemptController();
        RouteAttemptControlRecord record = RouteAttemptController.emptyRecord(3);

        record = acceptedRecord(controller, record, request(), ControlRouteNames.REQUEST_ROUTE_ATTEMPT, "cmd-request", BASE_TIME);
        record = acceptedRecord(controller, record, new IssueProxyRoute(ROUTE_ATTEMPT_ID, BASE_TIME.plusSeconds(1)),
                ControlRouteNames.ISSUE_PROXY_ROUTE, "cmd-proxy", BASE_TIME.plusSeconds(1));
        record = acceptedRecord(controller, record, new TimeoutRouteAttempt(ROUTE_ATTEMPT_ID, BASE_TIME.plusSeconds(31)),
                ControlRouteNames.TIMEOUT_ROUTE_ATTEMPT, "cmd-timeout", BASE_TIME.plusSeconds(31));

        assertEquals(RouteAttemptLifecycleStatus.TIMED_OUT, record.snapshot().orElseThrow().status());

        record = acceptedRecord(controller, record, new RetryRouteAttempt(ROUTE_ATTEMPT_ID, BASE_TIME.plusSeconds(32), BASE_TIME.plusSeconds(90)),
                ControlRouteNames.RETRY_ROUTE_ATTEMPT, "cmd-retry", BASE_TIME.plusSeconds(32));

        RouteAttemptSnapshot snapshot = record.snapshot().orElseThrow();
        assertEquals(RouteAttemptLifecycleStatus.CREATED, snapshot.status());
        assertEquals(1, snapshot.retryCount());
        assertEquals(BASE_TIME.plusSeconds(90), snapshot.deadlineAt());
    }

    @Test
    void acknowledgementAfterTimeoutIsRejected() {
        RouteAttemptController controller = new RouteAttemptController();
        RouteAttemptControlRecord record = RouteAttemptController.emptyRecord(3);

        record = acceptedRecord(controller, record, request(), ControlRouteNames.REQUEST_ROUTE_ATTEMPT, "cmd-request", BASE_TIME);
        record = acceptedRecord(controller, record, new TimeoutRouteAttempt(ROUTE_ATTEMPT_ID, BASE_TIME.plusSeconds(31)),
                ControlRouteNames.TIMEOUT_ROUTE_ATTEMPT, "cmd-timeout", BASE_TIME.plusSeconds(31));

        RouteAttemptDecision decision = controller.handle(command(
                new AcknowledgeRouteAttempt(ROUTE_ATTEMPT_ID, BASE_TIME.plusSeconds(32)),
                ControlRouteNames.ACKNOWLEDGE_ROUTE_ATTEMPT,
                "cmd-ack",
                "idem-ack",
                BASE_TIME.plusSeconds(32),
                Optional.of(record.revision()),
                PRINCIPAL_ID), record);

        assertEquals(RouteAttemptDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(RouteAttemptRejectionReason.INVALID_TRANSITION), decision.receipt().rejectionReason());
    }

    @Test
    void principalMismatchRejectsBeforeMutation() {
        RouteAttemptController controller = new RouteAttemptController();
        RouteAttemptControlRecord record = RouteAttemptController.emptyRecord(3);

        RouteAttemptDecision decision = controller.handle(command(
                request(),
                ControlRouteNames.REQUEST_ROUTE_ATTEMPT,
                "cmd-request",
                "idem-request",
                BASE_TIME,
                Optional.empty(),
                new PrincipalId("principal-attacker")), record);

        assertEquals(RouteAttemptDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(RouteAttemptRejectionReason.PRINCIPAL_MISMATCH), decision.receipt().rejectionReason());
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
                PRINCIPAL_ID), record);
        assertEquals(RouteAttemptDecisionStatus.ACCEPTED, decision.status());
        events.addAll(decision.events());
        return decision;
    }

    private static RouteAttemptControlRecord acceptedRecord(
            RouteAttemptController controller,
            RouteAttemptControlRecord record,
            RouteAttemptCommand payload,
            CommandName commandName,
            String commandId,
            Instant receivedAt) {
        return accept(controller, record, payload, commandName, commandId, receivedAt, new ArrayList<>()).record();
    }

    private static <T extends RouteAttemptCommand> RouteAttemptControlCommand<T> command(
            T payload,
            CommandName commandName,
            String commandId,
            String idempotencyKey,
            Instant receivedAt,
            Optional<Revision> expectedRevision,
            PrincipalId authenticatedPrincipal) {
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
                3,
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
                new RouteId("route-1"),
                new SessionId("session-route-1"),
                new SlotId("slot-agones-1"),
                List.of(new SubjectId(UUID.fromString("00000000-0000-0000-0000-000000000001"))),
                List.of(new InstanceId("instance-velocity-1")),
                new PresenceId("presence-1"),
                new InstanceId("instance-paper-1"),
                new ResolvedManifestId("manifest-1"),
                BASE_TIME,
                BASE_TIME.plusSeconds(30),
                trace());
    }

    private static TraceEnvelope trace() {
        return new TraceEnvelope(
                "trace-route-attempt",
                "span-route-attempt",
                Optional.empty(),
                BASE_TIME,
                "route-controller-test",
                new InstanceId("instance-controller-route"));
    }
}
