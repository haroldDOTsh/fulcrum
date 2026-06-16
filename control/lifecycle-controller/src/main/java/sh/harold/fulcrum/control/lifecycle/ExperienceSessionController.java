package sh.harold.fulcrum.control.lifecycle;

import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.Revision;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ExperienceSessionController {
    private final Map<IdempotencyKey, StoredExperienceSessionDecision> idempotencyLedger = new HashMap<>();

    public ExperienceSessionDecision handle(
            ExperienceSessionControlCommand<? extends ExperienceSessionCommand> command,
            ExperienceSessionControlRecord currentRecord) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(currentRecord, "currentRecord");

        Optional<ExperienceSessionRejectionReason> trustBoundaryRejection = trustBoundaryRejection(command, currentRecord);
        if (trustBoundaryRejection.isPresent()) {
            return ExperienceSessionDecision.rejected(
                    trustBoundaryRejection.orElseThrow(),
                    currentRecord.revision(),
                    currentRecord,
                    command.envelope().traceEnvelope(),
                    command.fencingEpoch(),
                    command.envelope().idempotencyKey().value(),
                    command.envelope().commandId().value());
        }

        StoredExperienceSessionDecision stored = idempotencyLedger.get(command.envelope().idempotencyKey());
        if (stored != null) {
            if (stored.payloadFingerprint().equals(command.payloadFingerprint())) {
                return stored.decision().asReplay();
            }
            return ExperienceSessionDecision.rejected(
                    ExperienceSessionRejectionReason.IDEMPOTENCY_CONFLICT,
                    currentRecord.revision(),
                    currentRecord,
                    command.envelope().traceEnvelope(),
                    command.fencingEpoch(),
                    command.envelope().idempotencyKey().value(),
                    command.envelope().commandId().value());
        }

        Optional<ExperienceSessionRejectionReason> commandRejection = commandRejection(command, currentRecord);
        if (commandRejection.isPresent()) {
            ExperienceSessionDecision decision = ExperienceSessionDecision.rejected(
                    commandRejection.orElseThrow(),
                    currentRecord.revision(),
                    currentRecord,
                    command.envelope().traceEnvelope(),
                    command.fencingEpoch(),
                    command.envelope().idempotencyKey().value(),
                    command.envelope().commandId().value());
            idempotencyLedger.put(command.envelope().idempotencyKey(), new StoredExperienceSessionDecision(command.payloadFingerprint(), decision));
            return decision;
        }

        ExperienceSessionDecision decision = accepted(command, currentRecord);
        idempotencyLedger.put(command.envelope().idempotencyKey(), new StoredExperienceSessionDecision(command.payloadFingerprint(), decision));
        return decision;
    }

    public static ExperienceSessionControlRecord replay(long fencingEpoch, List<ExperienceSessionEvent> events) {
        Objects.requireNonNull(events, "events");
        ExperienceSessionControlRecord record = ExperienceSessionControlRecord.empty(fencingEpoch);
        for (ExperienceSessionEvent event : events) {
            record = record.withRecord(event.revision(), event.sessionRecord());
        }
        return record;
    }

    public static ExperienceSessionControlRecord emptyRecord(long fencingEpoch) {
        return ExperienceSessionControlRecord.empty(fencingEpoch);
    }

    private static Optional<ExperienceSessionRejectionReason> trustBoundaryRejection(
            ExperienceSessionControlCommand<? extends ExperienceSessionCommand> command,
            ExperienceSessionControlRecord currentRecord) {
        if (!command.envelope().principalId().equals(command.authenticatedPrincipal())) {
            return Optional.of(ExperienceSessionRejectionReason.PRINCIPAL_MISMATCH);
        }
        if (command.fencingEpoch() != currentRecord.fencingEpoch()) {
            return Optional.of(ExperienceSessionRejectionReason.STALE_FENCING_EPOCH);
        }
        return Optional.empty();
    }

    private static Optional<ExperienceSessionRejectionReason> commandRejection(
            ExperienceSessionControlCommand<? extends ExperienceSessionCommand> command,
            ExperienceSessionControlRecord currentRecord) {
        boolean expired = command.envelope().deadlineAt()
                .map(deadline -> !deadline.isAfter(command.receivedAt()))
                .orElse(false);
        if (expired) {
            return Optional.of(ExperienceSessionRejectionReason.DEADLINE_EXPIRED);
        }
        if (command.expectedRevision().isPresent() && !command.expectedRevision().orElseThrow().equals(currentRecord.revision())) {
            return Optional.of(ExperienceSessionRejectionReason.REVISION_MISMATCH);
        }
        if (!command.envelope().aggregateId().equals(ControlLifecycleNames.sessionAggregateId(command.envelope().payload().sessionId()))) {
            return Optional.of(ExperienceSessionRejectionReason.AGGREGATE_MISMATCH);
        }
        if (!command.envelope().contractName().equals(ControlLifecycleNames.SESSION_CONTRACT)) {
            return Optional.of(ExperienceSessionRejectionReason.CONTRACT_MISMATCH);
        }
        return transitionRejection(command.envelope().payload(), currentRecord);
    }

    private static Optional<ExperienceSessionRejectionReason> transitionRejection(
            ExperienceSessionCommand command,
            ExperienceSessionControlRecord currentRecord) {
        Optional<ExperienceSessionRecord> current = currentRecord.sessionRecord();
        if (command instanceof RequestExperienceSession) {
            return current.isPresent()
                    ? Optional.of(ExperienceSessionRejectionReason.SESSION_ALREADY_EXISTS)
                    : Optional.empty();
        }
        if (current.isEmpty()) {
            return Optional.of(ExperienceSessionRejectionReason.SESSION_NOT_REQUESTED);
        }
        ExperienceSessionStatus status = current.orElseThrow().status();
        if (status == ExperienceSessionStatus.ENDED || status == ExperienceSessionStatus.FAILED) {
            return Optional.of(ExperienceSessionRejectionReason.SESSION_TERMINAL);
        }
        if (command instanceof PlaceExperienceSession) {
            return status == ExperienceSessionStatus.REQUESTED
                    ? Optional.empty()
                    : Optional.of(ExperienceSessionRejectionReason.SESSION_NOT_REQUESTED);
        }
        if (command instanceof ActivateExperienceSession) {
            return status == ExperienceSessionStatus.PLACED
                    ? Optional.empty()
                    : Optional.of(ExperienceSessionRejectionReason.SESSION_NOT_PLACED);
        }
        if (command instanceof EndExperienceSession) {
            return status == ExperienceSessionStatus.ACTIVE
                    ? Optional.empty()
                    : Optional.of(ExperienceSessionRejectionReason.SESSION_NOT_ACTIVE);
        }
        return Optional.of(ExperienceSessionRejectionReason.UNKNOWN_COMMAND);
    }

    private static ExperienceSessionDecision accepted(
            ExperienceSessionControlCommand<? extends ExperienceSessionCommand> command,
            ExperienceSessionControlRecord currentRecord) {
        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        ExperienceSessionRecord nextSessionRecord = nextSessionRecord(command.envelope().payload(), currentRecord);
        ExperienceSessionControlRecord nextRecord = currentRecord.withRecord(nextRevision, nextSessionRecord);
        ExperienceSessionEvent event = ExperienceSessionEvent.from(command, nextRevision, nextSessionRecord);
        ExperienceSessionReceipt receipt = ExperienceSessionReceipt.accepted(
                nextSessionRecord,
                nextRevision,
                command.fencingEpoch(),
                command.envelope().idempotencyKey().value(),
                command.envelope().commandId().value());
        return ExperienceSessionDecision.accepted(
                nextRevision,
                nextRecord,
                receipt,
                List.of(event),
                List.of(
                        new ExperienceSessionEmission(ExperienceSessionEmissionKind.EVENT, event.eventKey(), event.wireValue()),
                        new ExperienceSessionEmission(
                                ExperienceSessionEmissionKind.STATE,
                                ControlLifecycleNames.sessionStateKey(nextSessionRecord.sessionId()),
                                nextSessionRecord.wireValue(nextRevision)),
                        new ExperienceSessionEmission(ExperienceSessionEmissionKind.RESPONSE, command.envelope().commandId().value(), receipt.wireValue())));
    }

    private static ExperienceSessionRecord nextSessionRecord(
            ExperienceSessionCommand command,
            ExperienceSessionControlRecord currentRecord) {
        if (command instanceof RequestExperienceSession request) {
            return ExperienceSessionRecord.from(request);
        }
        ExperienceSessionRecord current = currentRecord.sessionRecord().orElseThrow();
        if (command instanceof PlaceExperienceSession place) {
            return current.place(place);
        }
        if (command instanceof ActivateExperienceSession activate) {
            return current.activate(activate);
        }
        if (command instanceof EndExperienceSession end) {
            return current.end(end);
        }
        throw new IllegalArgumentException("unknown ExperienceSession command");
    }
}

record StoredExperienceSessionDecision(String payloadFingerprint, ExperienceSessionDecision decision) {
    StoredExperienceSessionDecision {
        payloadFingerprint = ControlLifecycleStrings.requireNonBlank(payloadFingerprint, "payloadFingerprint");
        decision = Objects.requireNonNull(decision, "decision");
    }
}
