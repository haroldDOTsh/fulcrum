package sh.harold.fulcrum.control.lifecycle;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SessionId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LifecycleTraceControllerTest {
    private static final LifecycleTraceId TRACE_ID = new LifecycleTraceId("trace-session-flow");
    private static final PrincipalId PRINCIPAL_ID = new PrincipalId("principal-controller-lifecycle");
    private static final SessionId SESSION_ID = new SessionId("session-arena-1");
    private static final ResolvedManifestId MANIFEST_ID = new ResolvedManifestId("manifest-arena-1");
    private static final Instant BASE_TIME = Instant.parse("2026-06-16T15:00:00Z");

    @Test
    void recordsLifecycleTraceAcrossQueueRouteHostAndSessionPhases() {
        LifecycleTraceController controller = new LifecycleTraceController();
        LifecycleTraceControlRecord record = LifecycleTraceController.emptyRecord(13, TRACE_ID);
        List<LifecycleTraceEvent> events = new ArrayList<>();

        record = acceptedRecord(controller, record, observation(LifecyclePhase.QUEUE_INTENT_SUBMITTED, "queue-intent", "queue-1", Optional.empty(), Optional.empty(), 0),
                "cmd-queue", BASE_TIME, events);
        record = acceptedRecord(controller, record, observation(LifecyclePhase.ROSTER_INTENT_FORMED, "roster-intent", "roster-1", Optional.empty(), Optional.empty(), 1),
                "cmd-roster", BASE_TIME.plusSeconds(1), events);
        record = acceptedRecord(controller, record, observation(LifecyclePhase.ALLOCATION_CLAIMED, "slot", "slot-instance-paper-1", Optional.of(SESSION_ID), Optional.of(MANIFEST_ID), 2),
                "cmd-allocation", BASE_TIME.plusSeconds(2), events);
        record = acceptedRecord(controller, record, observation(LifecyclePhase.ROUTE_ATTEMPT_CREATED, "route-attempt", "route-attempt-1", Optional.of(SESSION_ID), Optional.of(MANIFEST_ID), 3),
                "cmd-route", BASE_TIME.plusSeconds(3), events);
        record = acceptedRecord(controller, record, observation(LifecyclePhase.HOST_ATTACH_OBSERVED, "instance", "instance-paper-1", Optional.of(SESSION_ID), Optional.of(MANIFEST_ID), 4),
                "cmd-host-attach", BASE_TIME.plusSeconds(4), events);
        record = acceptedRecord(controller, record, observation(LifecyclePhase.SESSION_ACTIVE, "session", SESSION_ID.value(), Optional.of(SESSION_ID), Optional.of(MANIFEST_ID), 5),
                "cmd-session-active", BASE_TIME.plusSeconds(5), events);

        List<LifecycleTraceEntry> entries = record.traceRecord().entries();
        assertEquals(6, entries.size());
        assertEquals(LifecyclePhase.QUEUE_INTENT_SUBMITTED, entries.get(0).phase());
        assertEquals(LifecyclePhase.SESSION_ACTIVE, entries.get(5).phase());
        assertEquals(6, entries.get(5).sequence());
        assertTrue(entries.stream().allMatch(entry -> TRACE_ID.value().equals(entry.traceEnvelope().traceId())));
        assertEquals(Optional.of(SESSION_ID), entries.get(5).sessionId());
        assertEquals(Optional.of(MANIFEST_ID), entries.get(5).resolvedManifestId());
        assertEquals(record, LifecycleTraceController.replay(13, TRACE_ID, events));
    }

    @Test
    void duplicateObservationCommandReplaysWithoutNewTraceEntry() {
        LifecycleTraceController controller = new LifecycleTraceController();
        LifecycleTraceControlRecord record = LifecycleTraceController.emptyRecord(13, TRACE_ID);
        LifecycleTraceControlCommand<RecordLifecycleObservation> command = command(
                observation(LifecyclePhase.QUEUE_INTENT_SUBMITTED, "queue-intent", "queue-1", Optional.empty(), Optional.empty(), 0),
                "cmd-queue",
                "idem-queue",
                BASE_TIME,
                Optional.empty(),
                PRINCIPAL_ID);

        LifecycleTraceDecision first = controller.handle(command, record);
        LifecycleTraceDecision second = controller.handle(command, first.record());

        assertEquals(LifecycleTraceDecisionStatus.ACCEPTED, first.status());
        assertEquals(LifecycleTraceDecisionStatus.REPLAYED, second.status());
        assertEquals(first.receipt(), second.receipt());
        assertEquals(1, first.record().traceRecord().entries().size());
        assertTrue(second.events().isEmpty());
        assertTrue(second.emissions().isEmpty());
    }

    @Test
    void mismatchedTraceAggregateRejectsBeforeMutation() {
        LifecycleTraceController controller = new LifecycleTraceController();
        LifecycleTraceControlRecord record = LifecycleTraceController.emptyRecord(13, new LifecycleTraceId("trace-other"));

        LifecycleTraceDecision decision = controller.handle(command(
                observation(LifecyclePhase.QUEUE_INTENT_SUBMITTED, "queue-intent", "queue-1", Optional.empty(), Optional.empty(), 0),
                "cmd-queue",
                "idem-queue",
                BASE_TIME,
                Optional.empty(),
                PRINCIPAL_ID), record);

        assertEquals(LifecycleTraceDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(LifecycleTraceRejectionReason.TRACE_MISMATCH), decision.receipt().rejectionReason());
        assertTrue(record.traceRecord().entries().isEmpty());
    }

    @Test
    void principalMismatchRejectsBeforeMutation() {
        LifecycleTraceController controller = new LifecycleTraceController();
        LifecycleTraceControlRecord record = LifecycleTraceController.emptyRecord(13, TRACE_ID);

        LifecycleTraceDecision decision = controller.handle(command(
                observation(LifecyclePhase.QUEUE_INTENT_SUBMITTED, "queue-intent", "queue-1", Optional.empty(), Optional.empty(), 0),
                "cmd-queue",
                "idem-queue",
                BASE_TIME,
                Optional.empty(),
                new PrincipalId("principal-attacker")), record);

        assertEquals(LifecycleTraceDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(LifecycleTraceRejectionReason.PRINCIPAL_MISMATCH), decision.receipt().rejectionReason());
        assertTrue(record.traceRecord().entries().isEmpty());
    }

    private static LifecycleTraceControlRecord acceptedRecord(
            LifecycleTraceController controller,
            LifecycleTraceControlRecord record,
            RecordLifecycleObservation payload,
            String commandId,
            Instant receivedAt,
            List<LifecycleTraceEvent> events) {
        LifecycleTraceDecision decision = controller.handle(command(
                payload,
                commandId,
                "idem-" + commandId,
                receivedAt,
                Optional.of(record.revision()),
                PRINCIPAL_ID), record);
        assertEquals(LifecycleTraceDecisionStatus.ACCEPTED, decision.status());
        events.addAll(decision.events());
        return decision.record();
    }

    private static LifecycleTraceControlCommand<RecordLifecycleObservation> command(
            RecordLifecycleObservation payload,
            String commandId,
            String idempotencyKey,
            Instant receivedAt,
            Optional<Revision> expectedRevision,
            PrincipalId authenticatedPrincipal) {
        return new LifecycleTraceControlCommand<>(
                new CommandEnvelope<>(
                        new CommandId(commandId),
                        new IdempotencyKey(idempotencyKey),
                        PRINCIPAL_ID,
                        aggregateId(payload),
                        ControlLifecycleNames.TRACE_CONTRACT,
                        ControlLifecycleNames.RECORD_LIFECYCLE_OBSERVATION,
                        payload.traceEnvelope(),
                        Optional.of(receivedAt.plusSeconds(30)),
                        payload),
                authenticatedPrincipal,
                13,
                expectedRevision,
                payload.phase().name() + ":" + commandId,
                receivedAt);
    }

    private static AggregateId aggregateId(RecordLifecycleObservation payload) {
        return ControlLifecycleNames.traceAggregateId(payload.traceId());
    }

    private static RecordLifecycleObservation observation(
            LifecyclePhase phase,
            String aggregateType,
            String aggregateId,
            Optional<SessionId> sessionId,
            Optional<ResolvedManifestId> resolvedManifestId,
            long observedOffsetSeconds) {
        Instant observedAt = BASE_TIME.plusSeconds(observedOffsetSeconds);
        return new RecordLifecycleObservation(
                TRACE_ID,
                phase,
                aggregateType,
                aggregateId,
                sessionId,
                resolvedManifestId,
                observedAt,
                trace("span-" + phase.name().toLowerCase(), observedAt));
    }

    private static TraceEnvelope trace(String spanId, Instant createdAt) {
        return new TraceEnvelope(
                TRACE_ID.value(),
                spanId,
                Optional.empty(),
                createdAt,
                "lifecycle-controller-test",
                new InstanceId("instance-controller-lifecycle"));
    }
}
