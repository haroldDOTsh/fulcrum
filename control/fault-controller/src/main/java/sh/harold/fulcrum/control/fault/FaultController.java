package sh.harold.fulcrum.control.fault;

import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.Revision;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class FaultController {
    private final Map<IdempotencyKey, StoredFaultDecision> idempotencyLedger = new HashMap<>();

    public FaultDecision handle(
            FaultControlCommand<? extends FaultCommand> command,
            FaultControlRecord currentRecord) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(currentRecord, "currentRecord");

        Optional<FaultRejectionReason> trustBoundaryRejection = trustBoundaryRejection(command, currentRecord);
        if (trustBoundaryRejection.isPresent()) {
            return FaultDecision.rejected(
                    trustBoundaryRejection.orElseThrow(),
                    currentRecord.revision(),
                    currentRecord,
                    command.envelope().traceEnvelope(),
                    command.fencingEpoch(),
                    command.envelope().idempotencyKey().value(),
                    command.envelope().commandId().value());
        }

        StoredFaultDecision stored = idempotencyLedger.get(command.envelope().idempotencyKey());
        if (stored != null) {
            if (stored.payloadFingerprint().equals(command.payloadFingerprint())) {
                return stored.decision().asReplay();
            }
            return FaultDecision.rejected(
                    FaultRejectionReason.IDEMPOTENCY_CONFLICT,
                    currentRecord.revision(),
                    currentRecord,
                    command.envelope().traceEnvelope(),
                    command.fencingEpoch(),
                    command.envelope().idempotencyKey().value(),
                    command.envelope().commandId().value());
        }

        Optional<FaultRejectionReason> commandRejection = commandRejection(command, currentRecord);
        if (commandRejection.isPresent()) {
            FaultDecision decision = FaultDecision.rejected(
                    commandRejection.orElseThrow(),
                    currentRecord.revision(),
                    currentRecord,
                    command.envelope().traceEnvelope(),
                    command.fencingEpoch(),
                    command.envelope().idempotencyKey().value(),
                    command.envelope().commandId().value());
            idempotencyLedger.put(command.envelope().idempotencyKey(), new StoredFaultDecision(command.payloadFingerprint(), decision));
            return decision;
        }

        FaultDecision decision = accepted(command, currentRecord);
        idempotencyLedger.put(command.envelope().idempotencyKey(), new StoredFaultDecision(command.payloadFingerprint(), decision));
        return decision;
    }

    public static FaultControlRecord replay(long fencingEpoch, List<FaultEvent> events) {
        Objects.requireNonNull(events, "events");
        FaultControlRecord record = FaultControlRecord.empty(fencingEpoch);
        for (FaultEvent event : events) {
            record = record.withRecord(event.revision(), event.faultRecord());
        }
        return record;
    }

    public static FaultControlRecord emptyRecord(long fencingEpoch) {
        return FaultControlRecord.empty(fencingEpoch);
    }

    private static Optional<FaultRejectionReason> trustBoundaryRejection(
            FaultControlCommand<? extends FaultCommand> command,
            FaultControlRecord currentRecord) {
        if (!command.envelope().principalId().equals(command.authenticatedPrincipal())) {
            return Optional.of(FaultRejectionReason.PRINCIPAL_MISMATCH);
        }
        if (command.fencingEpoch() != currentRecord.fencingEpoch()) {
            return Optional.of(FaultRejectionReason.STALE_FENCING_EPOCH);
        }
        return Optional.empty();
    }

    private static Optional<FaultRejectionReason> commandRejection(
            FaultControlCommand<? extends FaultCommand> command,
            FaultControlRecord currentRecord) {
        boolean expired = command.envelope().deadlineAt()
                .map(deadline -> !deadline.isAfter(command.receivedAt()))
                .orElse(false);
        if (expired) {
            return Optional.of(FaultRejectionReason.DEADLINE_EXPIRED);
        }
        if (command.expectedRevision().isPresent() && !command.expectedRevision().orElseThrow().equals(currentRecord.revision())) {
            return Optional.of(FaultRejectionReason.REVISION_MISMATCH);
        }
        if (!command.envelope().aggregateId().equals(ControlFaultNames.aggregateId(command.envelope().payload().faultId()))) {
            return Optional.of(FaultRejectionReason.AGGREGATE_MISMATCH);
        }
        if (!command.envelope().contractName().equals(ControlFaultNames.CONTRACT)) {
            return Optional.of(FaultRejectionReason.CONTRACT_MISMATCH);
        }
        return transitionRejection(command.envelope().payload(), currentRecord);
    }

    private static Optional<FaultRejectionReason> transitionRejection(
            FaultCommand command,
            FaultControlRecord currentRecord) {
        Optional<FaultRecord> current = currentRecord.faultRecord();
        if (command instanceof RecordFault recordFault) {
            if (current.isEmpty()) {
                return Optional.empty();
            }
            FaultRecord faultRecord = current.orElseThrow();
            if (faultRecord.quarantineState() == QuarantineState.RELEASED) {
                return Optional.of(FaultRejectionReason.FAULT_ALREADY_RELEASED);
            }
            return sameFaultIdentity(faultRecord, recordFault)
                    ? Optional.empty()
                    : Optional.of(FaultRejectionReason.FAULT_IDENTITY_MISMATCH);
        }
        if (command instanceof ReleaseFault) {
            if (current.isEmpty()) {
                return Optional.of(FaultRejectionReason.FAULT_NOT_FOUND);
            }
            return current.orElseThrow().quarantineState() == QuarantineState.RELEASED
                    ? Optional.of(FaultRejectionReason.FAULT_ALREADY_RELEASED)
                    : Optional.empty();
        }
        return Optional.of(FaultRejectionReason.UNKNOWN_COMMAND);
    }

    private static boolean sameFaultIdentity(FaultRecord current, RecordFault command) {
        return current.targetType() == command.targetType()
                && current.targetId().equals(command.targetId())
                && current.scope().equals(command.scope())
                && current.reason().equals(command.reason());
    }

    private static FaultDecision accepted(
            FaultControlCommand<? extends FaultCommand> command,
            FaultControlRecord currentRecord) {
        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        FaultRecord nextFaultRecord = nextFaultRecord(command.envelope().payload(), currentRecord);
        FaultControlRecord nextRecord = currentRecord.withRecord(nextRevision, nextFaultRecord);
        FaultEvent event = FaultEvent.from(command, nextRevision, nextFaultRecord);
        FaultReceipt receipt = FaultReceipt.accepted(
                nextFaultRecord,
                nextRevision,
                command.fencingEpoch(),
                command.envelope().idempotencyKey().value(),
                command.envelope().commandId().value());
        return FaultDecision.accepted(
                nextRevision,
                nextRecord,
                receipt,
                List.of(event),
                emissions(currentRecord.faultRecord(), nextFaultRecord, event, receipt));
    }

    private static FaultRecord nextFaultRecord(FaultCommand command, FaultControlRecord currentRecord) {
        if (command instanceof RecordFault recordFault) {
            return currentRecord.faultRecord()
                    .map(record -> record.recordAgain(recordFault))
                    .orElseGet(() -> FaultRecord.from(recordFault));
        }
        if (command instanceof ReleaseFault releaseFault) {
            return currentRecord.faultRecord().orElseThrow().release(releaseFault);
        }
        throw new IllegalArgumentException("unknown Fault command");
    }

    private static List<FaultControlEmission> emissions(
            Optional<FaultRecord> previous,
            FaultRecord next,
            FaultEvent event,
            FaultReceipt receipt) {
        List<FaultControlEmission> emissions = new java.util.ArrayList<>(List.of(
                new FaultControlEmission(FaultControlEmissionKind.EVENT, event.eventKey(), event.wireValue()),
                new FaultControlEmission(FaultControlEmissionKind.STATE, ControlFaultNames.stateKey(next.faultId()), next.wireValue(event.revision())),
                new FaultControlEmission(FaultControlEmissionKind.RESPONSE, receipt.commandId(), receipt.wireValue())));
        boolean newlyQuarantined = next.quarantineState() == QuarantineState.QUARANTINED
                && previous.map(record -> record.quarantineState() != QuarantineState.QUARANTINED).orElse(true);
        if (newlyQuarantined) {
            emissions.add(new FaultControlEmission(
                    FaultControlEmissionKind.QUARANTINE,
                    next.faultId().value(),
                    next.wireValue(event.revision())));
        }
        return List.copyOf(emissions);
    }
}

record StoredFaultDecision(String payloadFingerprint, FaultDecision decision) {
    StoredFaultDecision {
        payloadFingerprint = ControlFaultStrings.requireNonBlank(payloadFingerprint, "payloadFingerprint");
        decision = Objects.requireNonNull(decision, "decision");
    }
}
