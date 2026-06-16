package sh.harold.fulcrum.control.lifecycle;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
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

final class ExperienceSessionControllerTest {
    private static final SessionId SESSION_ID = new SessionId("session-arena-1");
    private static final ExperienceId EXPERIENCE_ID = new ExperienceId("experience-arena");
    private static final SlotId SLOT_ID = new SlotId("slot-instance-paper-1");
    private static final InstanceId INSTANCE_ID = new InstanceId("instance-paper-1");
    private static final ResolvedManifestId MANIFEST_ID = new ResolvedManifestId("manifest-arena-1");
    private static final SubjectId SUBJECT_ID = new SubjectId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final PrincipalId PRINCIPAL_ID = new PrincipalId("principal-controller-session");
    private static final Instant BASE_TIME = Instant.parse("2026-06-16T16:00:00Z");

    @Test
    void experienceSessionLifecycleRecordsPlacementActivationEndAndReplay() {
        ExperienceSessionController controller = new ExperienceSessionController();
        ExperienceSessionControlRecord record = ExperienceSessionController.emptyRecord(17);
        List<ExperienceSessionEvent> events = new ArrayList<>();

        record = acceptedRecord(controller, record, request(), ControlLifecycleNames.REQUEST_EXPERIENCE_SESSION, "cmd-request", BASE_TIME, events);
        record = acceptedRecord(controller, record, place(BASE_TIME.plusSeconds(1)), ControlLifecycleNames.PLACE_EXPERIENCE_SESSION, "cmd-place", BASE_TIME.plusSeconds(1), events);
        record = acceptedRecord(controller, record, activate(BASE_TIME.plusSeconds(2)), ControlLifecycleNames.ACTIVATE_EXPERIENCE_SESSION, "cmd-active", BASE_TIME.plusSeconds(2), events);
        record = acceptedRecord(controller, record, end(BASE_TIME.plusSeconds(3)), ControlLifecycleNames.END_EXPERIENCE_SESSION, "cmd-end", BASE_TIME.plusSeconds(3), events);

        ExperienceSessionRecord sessionRecord = record.sessionRecord().orElseThrow();
        assertEquals(ExperienceSessionStatus.ENDED, sessionRecord.status());
        assertEquals(Optional.of(SLOT_ID), sessionRecord.allocationSlotId());
        assertEquals(Optional.of(INSTANCE_ID), sessionRecord.instanceId());
        assertEquals(Optional.of(MANIFEST_ID), sessionRecord.resolvedManifestId());
        assertEquals(Optional.of(BASE_TIME.plusSeconds(2)), sessionRecord.activatedAt());
        assertEquals(Optional.of(BASE_TIME.plusSeconds(3)), sessionRecord.endedAt());
        assertEquals(Optional.of("completed"), sessionRecord.endReason());
        assertEquals(new Revision(4), record.revision());
        assertEquals(record, ExperienceSessionController.replay(17, events));
    }

    @Test
    void duplicateSessionCommandReplaysStoredDecisionWithoutNewEvents() {
        ExperienceSessionController controller = new ExperienceSessionController();
        ExperienceSessionControlRecord record = ExperienceSessionController.emptyRecord(17);
        ExperienceSessionControlCommand<RequestExperienceSession> command = command(
                request(),
                ControlLifecycleNames.REQUEST_EXPERIENCE_SESSION,
                "cmd-request",
                "idem-request",
                BASE_TIME,
                Optional.empty(),
                PRINCIPAL_ID);

        ExperienceSessionDecision first = controller.handle(command, record);
        ExperienceSessionDecision second = controller.handle(command, first.record());

        assertEquals(ExperienceSessionDecisionStatus.ACCEPTED, first.status());
        assertEquals(ExperienceSessionDecisionStatus.REPLAYED, second.status());
        assertEquals(first.receipt(), second.receipt());
        assertTrue(second.events().isEmpty());
        assertTrue(second.emissions().isEmpty());
    }

    @Test
    void activationBeforePlacementIsRejected() {
        ExperienceSessionController controller = new ExperienceSessionController();
        ExperienceSessionControlRecord record = ExperienceSessionController.emptyRecord(17);
        record = acceptedRecord(controller, record, request(), ControlLifecycleNames.REQUEST_EXPERIENCE_SESSION, "cmd-request", BASE_TIME, new ArrayList<>());

        ExperienceSessionDecision decision = controller.handle(command(
                activate(BASE_TIME.plusSeconds(1)),
                ControlLifecycleNames.ACTIVATE_EXPERIENCE_SESSION,
                "cmd-active",
                "idem-active",
                BASE_TIME.plusSeconds(1),
                Optional.of(record.revision()),
                PRINCIPAL_ID), record);

        assertEquals(ExperienceSessionDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(ExperienceSessionRejectionReason.SESSION_NOT_PLACED), decision.receipt().rejectionReason());
        assertEquals(ExperienceSessionStatus.REQUESTED, record.sessionRecord().orElseThrow().status());
    }

    @Test
    void principalMismatchRejectsBeforeMutation() {
        ExperienceSessionController controller = new ExperienceSessionController();
        ExperienceSessionControlRecord record = ExperienceSessionController.emptyRecord(17);

        ExperienceSessionDecision decision = controller.handle(command(
                request(),
                ControlLifecycleNames.REQUEST_EXPERIENCE_SESSION,
                "cmd-request",
                "idem-request",
                BASE_TIME,
                Optional.empty(),
                new PrincipalId("principal-attacker")), record);

        assertEquals(ExperienceSessionDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(ExperienceSessionRejectionReason.PRINCIPAL_MISMATCH), decision.receipt().rejectionReason());
        assertTrue(record.sessionRecord().isEmpty());
    }

    private static ExperienceSessionControlRecord acceptedRecord(
            ExperienceSessionController controller,
            ExperienceSessionControlRecord record,
            ExperienceSessionCommand payload,
            CommandName commandName,
            String commandId,
            Instant receivedAt,
            List<ExperienceSessionEvent> events) {
        ExperienceSessionDecision decision = controller.handle(command(
                payload,
                commandName,
                commandId,
                "idem-" + commandId,
                receivedAt,
                Optional.of(record.revision()),
                PRINCIPAL_ID), record);
        assertEquals(ExperienceSessionDecisionStatus.ACCEPTED, decision.status());
        events.addAll(decision.events());
        return decision.record();
    }

    private static <T extends ExperienceSessionCommand> ExperienceSessionControlCommand<T> command(
            T payload,
            CommandName commandName,
            String commandId,
            String idempotencyKey,
            Instant receivedAt,
            Optional<Revision> expectedRevision,
            PrincipalId authenticatedPrincipal) {
        return new ExperienceSessionControlCommand<>(
                new CommandEnvelope<>(
                        new CommandId(commandId),
                        new IdempotencyKey(idempotencyKey),
                        PRINCIPAL_ID,
                        aggregateId(payload),
                        ControlLifecycleNames.SESSION_CONTRACT,
                        commandName,
                        payloadTrace(payload),
                        Optional.of(receivedAt.plusSeconds(30)),
                        payload),
                authenticatedPrincipal,
                17,
                expectedRevision,
                payload.getClass().getSimpleName() + ":" + commandId,
                receivedAt);
    }

    private static AggregateId aggregateId(ExperienceSessionCommand payload) {
        return ControlLifecycleNames.sessionAggregateId(payload.sessionId());
    }

    private static RequestExperienceSession request() {
        return new RequestExperienceSession(
                SESSION_ID,
                EXPERIENCE_ID,
                Optional.of("standard"),
                "temporary",
                List.of(SUBJECT_ID),
                BASE_TIME,
                trace("span-request", BASE_TIME));
    }

    private static PlaceExperienceSession place(Instant placedAt) {
        return new PlaceExperienceSession(
                SESSION_ID,
                SLOT_ID,
                INSTANCE_ID,
                MANIFEST_ID,
                placedAt,
                trace("span-place", placedAt));
    }

    private static ActivateExperienceSession activate(Instant activatedAt) {
        return new ActivateExperienceSession(SESSION_ID, activatedAt, trace("span-active", activatedAt));
    }

    private static EndExperienceSession end(Instant endedAt) {
        return new EndExperienceSession(SESSION_ID, "completed", endedAt, trace("span-end", endedAt));
    }

    private static TraceEnvelope payloadTrace(ExperienceSessionCommand payload) {
        if (payload instanceof RequestExperienceSession request) {
            return request.traceEnvelope();
        }
        if (payload instanceof PlaceExperienceSession place) {
            return place.traceEnvelope();
        }
        if (payload instanceof ActivateExperienceSession activate) {
            return activate.traceEnvelope();
        }
        if (payload instanceof EndExperienceSession end) {
            return end.traceEnvelope();
        }
        throw new IllegalArgumentException("unknown ExperienceSession command");
    }

    private static TraceEnvelope trace(String spanId, Instant createdAt) {
        return new TraceEnvelope(
                "trace-session-record",
                spanId,
                Optional.empty(),
                createdAt,
                "experience-session-controller-test",
                new InstanceId("instance-controller-session"));
    }
}
