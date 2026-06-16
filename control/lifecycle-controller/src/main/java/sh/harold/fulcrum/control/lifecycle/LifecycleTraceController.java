package sh.harold.fulcrum.control.lifecycle;

import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.Revision;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class LifecycleTraceController {
    private final Map<IdempotencyKey, StoredLifecycleTraceDecision> idempotencyLedger = new HashMap<>();

    public LifecycleTraceDecision handle(
            LifecycleTraceControlCommand<? extends LifecycleTraceCommand> command,
            LifecycleTraceControlRecord currentRecord) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(currentRecord, "currentRecord");

        Optional<LifecycleTraceRejectionReason> trustBoundaryRejection = trustBoundaryRejection(command, currentRecord);
        if (trustBoundaryRejection.isPresent()) {
            return LifecycleTraceDecision.rejected(
                    trustBoundaryRejection.orElseThrow(),
                    currentRecord.revision(),
                    currentRecord,
                    command.envelope().traceEnvelope(),
                    command.fencingEpoch(),
                    command.envelope().idempotencyKey().value(),
                    command.envelope().commandId().value());
        }

        StoredLifecycleTraceDecision stored = idempotencyLedger.get(command.envelope().idempotencyKey());
        if (stored != null) {
            if (stored.payloadFingerprint().equals(command.payloadFingerprint())) {
                return stored.decision().asReplay();
            }
            return LifecycleTraceDecision.rejected(
                    LifecycleTraceRejectionReason.IDEMPOTENCY_CONFLICT,
                    currentRecord.revision(),
                    currentRecord,
                    command.envelope().traceEnvelope(),
                    command.fencingEpoch(),
                    command.envelope().idempotencyKey().value(),
                    command.envelope().commandId().value());
        }

        Optional<LifecycleTraceRejectionReason> commandRejection = commandRejection(command, currentRecord);
        if (commandRejection.isPresent()) {
            LifecycleTraceDecision decision = LifecycleTraceDecision.rejected(
                    commandRejection.orElseThrow(),
                    currentRecord.revision(),
                    currentRecord,
                    command.envelope().traceEnvelope(),
                    command.fencingEpoch(),
                    command.envelope().idempotencyKey().value(),
                    command.envelope().commandId().value());
            idempotencyLedger.put(command.envelope().idempotencyKey(), new StoredLifecycleTraceDecision(command.payloadFingerprint(), decision));
            return decision;
        }

        LifecycleTraceDecision decision = accepted(command, currentRecord);
        idempotencyLedger.put(command.envelope().idempotencyKey(), new StoredLifecycleTraceDecision(command.payloadFingerprint(), decision));
        return decision;
    }

    public static LifecycleTraceControlRecord replay(long fencingEpoch, LifecycleTraceId traceId, List<LifecycleTraceEvent> events) {
        Objects.requireNonNull(events, "events");
        LifecycleTraceControlRecord record = LifecycleTraceControlRecord.empty(fencingEpoch, traceId);
        for (LifecycleTraceEvent event : events) {
            record = record.withRecord(event.revision(), event.traceRecord());
        }
        return record;
    }

    public static LifecycleTraceControlRecord emptyRecord(long fencingEpoch, LifecycleTraceId traceId) {
        return LifecycleTraceControlRecord.empty(fencingEpoch, traceId);
    }

    private static Optional<LifecycleTraceRejectionReason> trustBoundaryRejection(
            LifecycleTraceControlCommand<? extends LifecycleTraceCommand> command,
            LifecycleTraceControlRecord currentRecord) {
        if (!command.envelope().principalId().equals(command.authenticatedPrincipal())) {
            return Optional.of(LifecycleTraceRejectionReason.PRINCIPAL_MISMATCH);
        }
        if (command.fencingEpoch() != currentRecord.fencingEpoch()) {
            return Optional.of(LifecycleTraceRejectionReason.STALE_FENCING_EPOCH);
        }
        return Optional.empty();
    }

    private static Optional<LifecycleTraceRejectionReason> commandRejection(
            LifecycleTraceControlCommand<? extends LifecycleTraceCommand> command,
            LifecycleTraceControlRecord currentRecord) {
        boolean expired = command.envelope().deadlineAt()
                .map(deadline -> !deadline.isAfter(command.receivedAt()))
                .orElse(false);
        if (expired) {
            return Optional.of(LifecycleTraceRejectionReason.DEADLINE_EXPIRED);
        }
        if (command.expectedRevision().isPresent() && !command.expectedRevision().orElseThrow().equals(currentRecord.revision())) {
            return Optional.of(LifecycleTraceRejectionReason.REVISION_MISMATCH);
        }
        if (!command.envelope().aggregateId().equals(ControlLifecycleNames.traceAggregateId(command.envelope().payload().traceId()))) {
            return Optional.of(LifecycleTraceRejectionReason.AGGREGATE_MISMATCH);
        }
        if (!command.envelope().contractName().equals(ControlLifecycleNames.TRACE_CONTRACT)) {
            return Optional.of(LifecycleTraceRejectionReason.CONTRACT_MISMATCH);
        }
        if (!command.envelope().payload().traceId().equals(currentRecord.traceRecord().traceId())) {
            return Optional.of(LifecycleTraceRejectionReason.TRACE_MISMATCH);
        }
        if (!(command.envelope().payload() instanceof RecordLifecycleObservation)) {
            return Optional.of(LifecycleTraceRejectionReason.UNKNOWN_COMMAND);
        }
        return Optional.empty();
    }

    private static LifecycleTraceDecision accepted(
            LifecycleTraceControlCommand<? extends LifecycleTraceCommand> command,
            LifecycleTraceControlRecord currentRecord) {
        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        RecordLifecycleObservation observation = (RecordLifecycleObservation) command.envelope().payload();
        LifecycleTraceRecord nextTraceRecord = currentRecord.traceRecord().append(observation);
        LifecycleTraceControlRecord nextRecord = currentRecord.withRecord(nextRevision, nextTraceRecord);
        LifecycleTraceEvent event = LifecycleTraceEvent.from(command, nextRevision, nextTraceRecord);
        LifecycleTraceReceipt receipt = LifecycleTraceReceipt.accepted(
                nextTraceRecord,
                nextRevision,
                command.fencingEpoch(),
                command.envelope().idempotencyKey().value(),
                command.envelope().commandId().value(),
                command.envelope().traceEnvelope());
        return LifecycleTraceDecision.accepted(
                nextRevision,
                nextRecord,
                receipt,
                List.of(event),
                List.of(
                        new LifecycleTraceEmission(LifecycleTraceEmissionKind.EVENT, event.eventKey(), event.wireValue()),
                        new LifecycleTraceEmission(
                                LifecycleTraceEmissionKind.STATE,
                                ControlLifecycleNames.traceStateKey(nextTraceRecord.traceId()),
                                nextTraceRecord.wireValue(nextRevision)),
                        new LifecycleTraceEmission(LifecycleTraceEmissionKind.RESPONSE, command.envelope().commandId().value(), receipt.wireValue())));
    }
}

record StoredLifecycleTraceDecision(String payloadFingerprint, LifecycleTraceDecision decision) {
    StoredLifecycleTraceDecision {
        payloadFingerprint = ControlLifecycleStrings.requireNonBlank(payloadFingerprint, "payloadFingerprint");
        decision = Objects.requireNonNull(decision, "decision");
    }
}
