package sh.harold.fulcrum.control.fault;

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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FaultControllerTest {
    private static final FaultId FAULT_ID = new FaultId("fault-route-prep-1");
    private static final PrincipalId PRINCIPAL_ID = new PrincipalId("principal-controller-fault");
    private static final Instant BASE_TIME = Instant.parse("2026-06-16T14:00:00Z");

    @Test
    void repeatedFaultsQuarantineAndReplayFromEvents() {
        FaultController controller = new FaultController();
        FaultControlRecord record = FaultController.emptyRecord(11);
        List<FaultEvent> events = new ArrayList<>();

        FaultDecision first = accept(controller, record, recordFault("instance-paper-1", 2, BASE_TIME),
                ControlFaultNames.RECORD_FAULT, "cmd-record-1", BASE_TIME, events);
        record = first.record();
        assertEquals(QuarantineState.OBSERVED, record.faultRecord().orElseThrow().quarantineState());
        assertTrue(first.emissions().stream().noneMatch(emission -> emission.kind() == FaultControlEmissionKind.QUARANTINE));

        FaultDecision second = accept(controller, record, recordFault("instance-paper-1", 2, BASE_TIME.plusSeconds(1)),
                ControlFaultNames.RECORD_FAULT, "cmd-record-2", BASE_TIME.plusSeconds(1), events);
        record = second.record();

        FaultRecord faultRecord = record.faultRecord().orElseThrow();
        assertEquals(2, faultRecord.count());
        assertEquals(QuarantineState.QUARANTINED, faultRecord.quarantineState());
        assertTrue(second.emissions().stream().anyMatch(emission -> emission.kind() == FaultControlEmissionKind.QUARANTINE));
        assertEquals(record, FaultController.replay(11, events));
    }

    @Test
    void duplicateCommandReplaysStoredDecisionWithoutNewEvents() {
        FaultController controller = new FaultController();
        FaultControlRecord record = FaultController.emptyRecord(11);
        FaultControlCommand<RecordFault> command = command(
                recordFault("instance-paper-1", 1, BASE_TIME),
                ControlFaultNames.RECORD_FAULT,
                "cmd-record-1",
                "idem-record-1",
                BASE_TIME,
                Optional.empty(),
                PRINCIPAL_ID);

        FaultDecision first = controller.handle(command, record);
        FaultDecision second = controller.handle(command, first.record());

        assertEquals(FaultDecisionStatus.ACCEPTED, first.status());
        assertEquals(FaultDecisionStatus.REPLAYED, second.status());
        assertEquals(first.receipt(), second.receipt());
        assertTrue(second.events().isEmpty());
        assertTrue(second.emissions().isEmpty());
    }

    @Test
    void sameFaultIdWithDifferentTargetIsRejected() {
        FaultController controller = new FaultController();
        FaultControlRecord record = FaultController.emptyRecord(11);
        record = acceptedRecord(controller, record, recordFault("instance-paper-1", 3, BASE_TIME),
                ControlFaultNames.RECORD_FAULT, "cmd-record-1", BASE_TIME);

        FaultDecision decision = controller.handle(command(
                recordFault("instance-paper-2", 3, BASE_TIME.plusSeconds(1)),
                ControlFaultNames.RECORD_FAULT,
                "cmd-record-2",
                "idem-record-2",
                BASE_TIME.plusSeconds(1),
                Optional.of(record.revision()),
                PRINCIPAL_ID), record);

        assertEquals(FaultDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(FaultRejectionReason.FAULT_IDENTITY_MISMATCH), decision.receipt().rejectionReason());
        assertEquals(1, record.faultRecord().orElseThrow().count());
    }

    @Test
    void releaseMovesFaultOutOfQuarantineAndBlocksFurtherMutation() {
        FaultController controller = new FaultController();
        FaultControlRecord record = FaultController.emptyRecord(11);
        record = acceptedRecord(controller, record, recordFault("instance-paper-1", 1, BASE_TIME),
                ControlFaultNames.RECORD_FAULT, "cmd-record-1", BASE_TIME);
        record = acceptedRecord(controller, record, new ReleaseFault(FAULT_ID, "operator-cleared", BASE_TIME.plusSeconds(2), trace()),
                ControlFaultNames.RELEASE_FAULT, "cmd-release", BASE_TIME.plusSeconds(2));

        assertEquals(QuarantineState.RELEASED, record.faultRecord().orElseThrow().quarantineState());
        FaultDecision decision = controller.handle(command(
                recordFault("instance-paper-1", 1, BASE_TIME.plusSeconds(3)),
                ControlFaultNames.RECORD_FAULT,
                "cmd-record-2",
                "idem-record-2",
                BASE_TIME.plusSeconds(3),
                Optional.of(record.revision()),
                PRINCIPAL_ID), record);

        assertEquals(FaultDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(FaultRejectionReason.FAULT_ALREADY_RELEASED), decision.receipt().rejectionReason());
    }

    @Test
    void principalMismatchRejectsBeforeMutation() {
        FaultController controller = new FaultController();
        FaultControlRecord record = FaultController.emptyRecord(11);

        FaultDecision decision = controller.handle(command(
                recordFault("instance-paper-1", 1, BASE_TIME),
                ControlFaultNames.RECORD_FAULT,
                "cmd-record-1",
                "idem-record-1",
                BASE_TIME,
                Optional.empty(),
                new PrincipalId("principal-attacker")), record);

        assertEquals(FaultDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(FaultRejectionReason.PRINCIPAL_MISMATCH), decision.receipt().rejectionReason());
        assertTrue(record.faultRecord().isEmpty());
    }

    private static FaultDecision accept(
            FaultController controller,
            FaultControlRecord record,
            FaultCommand payload,
            CommandName commandName,
            String commandId,
            Instant receivedAt,
            List<FaultEvent> events) {
        FaultDecision decision = controller.handle(command(
                payload,
                commandName,
                commandId,
                "idem-" + commandId,
                receivedAt,
                Optional.of(record.revision()),
                PRINCIPAL_ID), record);
        assertEquals(FaultDecisionStatus.ACCEPTED, decision.status());
        events.addAll(decision.events());
        return decision;
    }

    private static FaultControlRecord acceptedRecord(
            FaultController controller,
            FaultControlRecord record,
            FaultCommand payload,
            CommandName commandName,
            String commandId,
            Instant receivedAt) {
        return accept(controller, record, payload, commandName, commandId, receivedAt, new ArrayList<>()).record();
    }

    private static <T extends FaultCommand> FaultControlCommand<T> command(
            T payload,
            CommandName commandName,
            String commandId,
            String idempotencyKey,
            Instant receivedAt,
            Optional<Revision> expectedRevision,
            PrincipalId authenticatedPrincipal) {
        return new FaultControlCommand<>(
                new CommandEnvelope<>(
                        new CommandId(commandId),
                        new IdempotencyKey(idempotencyKey),
                        PRINCIPAL_ID,
                        aggregateId(payload),
                        ControlFaultNames.CONTRACT,
                        commandName,
                        trace(),
                        Optional.of(receivedAt.plusSeconds(30)),
                        payload),
                authenticatedPrincipal,
                11,
                expectedRevision,
                payload.getClass().getSimpleName() + ":" + commandId,
                receivedAt);
    }

    private static AggregateId aggregateId(FaultCommand payload) {
        return ControlFaultNames.aggregateId(payload.faultId());
    }

    private static RecordFault recordFault(String targetId, int quarantineAfterCount, Instant observedAt) {
        return new RecordFault(
                FAULT_ID,
                FaultTargetType.INSTANCE,
                targetId,
                "pool-paper-arena",
                "route-preparation-failed",
                quarantineAfterCount,
                observedAt,
                trace());
    }

    private static TraceEnvelope trace() {
        return new TraceEnvelope(
                "trace-fault",
                "span-fault",
                Optional.empty(),
                BASE_TIME,
                "fault-controller-test",
                new InstanceId("instance-controller-fault"));
    }
}
