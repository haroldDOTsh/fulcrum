package sh.harold.fulcrum.control.queue;

import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class QueueRosterController {
    private final Map<IdempotencyKey, StoredQueueRosterDecision> idempotencyLedger = new HashMap<>();

    public QueueRosterDecision handle(
            QueueRosterControlCommand<? extends QueueRosterCommand> command,
            QueueRosterControlRecord currentRecord) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(currentRecord, "currentRecord");

        Optional<QueueRosterRejectionReason> trustBoundaryRejection = trustBoundaryRejection(command, currentRecord);
        if (trustBoundaryRejection.isPresent()) {
            return QueueRosterDecision.rejected(
                    trustBoundaryRejection.orElseThrow(),
                    currentRecord.revision(),
                    currentRecord,
                    command.envelope().traceEnvelope(),
                    command.fencingEpoch(),
                    command.envelope().idempotencyKey().value(),
                    command.envelope().commandId().value());
        }

        StoredQueueRosterDecision stored = idempotencyLedger.get(command.envelope().idempotencyKey());
        if (stored != null) {
            if (stored.payloadFingerprint().equals(command.payloadFingerprint())) {
                return stored.decision().asReplay();
            }
            return QueueRosterDecision.rejected(
                    QueueRosterRejectionReason.IDEMPOTENCY_CONFLICT,
                    currentRecord.revision(),
                    currentRecord,
                    command.envelope().traceEnvelope(),
                    command.fencingEpoch(),
                    command.envelope().idempotencyKey().value(),
                    command.envelope().commandId().value());
        }

        Optional<QueueRosterRejectionReason> commandRejection = commandRejection(command, currentRecord);
        if (commandRejection.isPresent()) {
            QueueRosterDecision decision = QueueRosterDecision.rejected(
                    commandRejection.orElseThrow(),
                    currentRecord.revision(),
                    currentRecord,
                    command.envelope().traceEnvelope(),
                    command.fencingEpoch(),
                    command.envelope().idempotencyKey().value(),
                    command.envelope().commandId().value());
            idempotencyLedger.put(command.envelope().idempotencyKey(), new StoredQueueRosterDecision(command.payloadFingerprint(), decision));
            return decision;
        }

        QueueRosterDecision decision = accepted(command, currentRecord);
        idempotencyLedger.put(command.envelope().idempotencyKey(), new StoredQueueRosterDecision(command.payloadFingerprint(), decision));
        return decision;
    }

    public static QueueRosterControlRecord replay(long fencingEpoch, List<QueueRosterEvent> events) {
        Objects.requireNonNull(events, "events");
        QueueRosterControlRecord record = QueueRosterControlRecord.empty(fencingEpoch);
        for (QueueRosterEvent event : events) {
            record = record.withState(event.revision(), event.state());
        }
        return record;
    }

    public static QueueRosterControlRecord emptyRecord(long fencingEpoch) {
        return QueueRosterControlRecord.empty(fencingEpoch);
    }

    private static Optional<QueueRosterRejectionReason> trustBoundaryRejection(
            QueueRosterControlCommand<? extends QueueRosterCommand> command,
            QueueRosterControlRecord currentRecord) {
        if (!command.envelope().principalId().equals(command.authenticatedPrincipal())) {
            return Optional.of(QueueRosterRejectionReason.PRINCIPAL_MISMATCH);
        }
        if (command.fencingEpoch() != currentRecord.fencingEpoch()) {
            return Optional.of(QueueRosterRejectionReason.STALE_FENCING_EPOCH);
        }
        return Optional.empty();
    }

    private static Optional<QueueRosterRejectionReason> commandRejection(
            QueueRosterControlCommand<? extends QueueRosterCommand> command,
            QueueRosterControlRecord currentRecord) {
        boolean expired = command.envelope().deadlineAt()
                .map(deadline -> !deadline.isAfter(command.receivedAt()))
                .orElse(false);
        if (expired) {
            return Optional.of(QueueRosterRejectionReason.DEADLINE_EXPIRED);
        }
        if (command.expectedRevision().isPresent() && !command.expectedRevision().orElseThrow().equals(currentRecord.revision())) {
            return Optional.of(QueueRosterRejectionReason.REVISION_MISMATCH);
        }
        if (!command.envelope().aggregateId().equals(ControlQueueNames.aggregateId(command.envelope().payload().partitionKey()))) {
            return Optional.of(QueueRosterRejectionReason.AGGREGATE_MISMATCH);
        }
        if (!command.envelope().contractName().equals(ControlQueueNames.CONTRACT)) {
            return Optional.of(QueueRosterRejectionReason.CONTRACT_MISMATCH);
        }
        return transitionRejection(command, currentRecord);
    }

    private static Optional<QueueRosterRejectionReason> transitionRejection(
            QueueRosterControlCommand<? extends QueueRosterCommand> command,
            QueueRosterControlRecord currentRecord) {
        QueueRosterCommand payload = command.envelope().payload();
        QueueRosterState state = currentRecord.state();
        if (payload instanceof SubmitQueueIntent submit) {
            if (state.queueIntent(submit.queueIntentId()).isPresent()) {
                return Optional.of(QueueRosterRejectionReason.QUEUE_INTENT_ALREADY_EXISTS);
            }
            return state.hasWaitingSubjectOverlap(submit.subjectIds())
                    ? Optional.of(QueueRosterRejectionReason.DUPLICATE_SUBJECT_IN_WAITING_QUEUE)
                    : Optional.empty();
        }
        if (payload instanceof CancelQueueIntent cancel) {
            return waitingQueueIntentRejection(state, cancel.queueIntentId());
        }
        if (payload instanceof ExpireQueueIntent expire) {
            return waitingQueueIntentRejection(state, expire.queueIntentId());
        }
        if (payload instanceof FormRosterIntent form) {
            return formRosterRejection(state, form);
        }
        return Optional.of(QueueRosterRejectionReason.UNKNOWN_COMMAND);
    }

    private static Optional<QueueRosterRejectionReason> waitingQueueIntentRejection(
            QueueRosterState state,
            QueueIntentId queueIntentId) {
        Optional<QueueIntentSnapshot> snapshot = state.queueIntent(queueIntentId);
        if (snapshot.isEmpty() || snapshot.orElseThrow().status() != QueueIntentStatus.WAITING) {
            return Optional.of(QueueRosterRejectionReason.QUEUE_INTENT_NOT_WAITING);
        }
        return Optional.empty();
    }

    private static Optional<QueueRosterRejectionReason> formRosterRejection(
            QueueRosterState state,
            FormRosterIntent command) {
        if (state.rosterIntent(command.rosterIntentId()).isPresent()) {
            return Optional.of(QueueRosterRejectionReason.ROSTER_INTENT_ALREADY_EXISTS);
        }
        if (command.queueIntentIds().isEmpty()) {
            return Optional.of(QueueRosterRejectionReason.EMPTY_ROSTER);
        }

        Set<SubjectId> selectedSubjects = new HashSet<>();
        int subjectCount = 0;
        for (QueueIntentId queueIntentId : command.queueIntentIds()) {
            Optional<QueueIntentSnapshot> optionalSnapshot = state.queueIntent(queueIntentId);
            if (optionalSnapshot.isEmpty() || optionalSnapshot.orElseThrow().status() != QueueIntentStatus.WAITING) {
                return Optional.of(QueueRosterRejectionReason.QUEUE_INTENT_NOT_WAITING);
            }
            QueueIntentSnapshot snapshot = optionalSnapshot.orElseThrow();
            if (!snapshot.partitionKey().equals(command.partitionKey())) {
                return Optional.of(QueueRosterRejectionReason.INCOMPATIBLE_QUEUE_INTENTS);
            }
            if (!snapshot.deadlineAt().isAfter(command.formedAt())) {
                return Optional.of(QueueRosterRejectionReason.QUEUE_INTENT_EXPIRED);
            }
            subjectCount += snapshot.subjectIds().size();
            for (SubjectId subjectId : snapshot.subjectIds()) {
                if (!selectedSubjects.add(subjectId)) {
                    return Optional.of(QueueRosterRejectionReason.DUPLICATE_SUBJECT_IN_ROSTER);
                }
            }
        }
        return subjectCount > command.maxSubjects()
                ? Optional.of(QueueRosterRejectionReason.ROSTER_CAPACITY_EXCEEDED)
                : Optional.empty();
    }

    private static QueueRosterDecision accepted(
            QueueRosterControlCommand<? extends QueueRosterCommand> command,
            QueueRosterControlRecord currentRecord) {
        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        QueueRosterState nextState = nextState(command, currentRecord.state());
        QueueRosterControlRecord nextRecord = currentRecord.withState(nextRevision, nextState);
        QueueRosterEvent event = QueueRosterEvent.from(command, nextRevision, nextState);
        QueueRosterReceipt receipt = QueueRosterReceipt.accepted(
                nextState,
                nextRevision,
                command.fencingEpoch(),
                command.envelope().idempotencyKey().value(),
                command.envelope().commandId().value(),
                command.envelope().traceEnvelope());
        return QueueRosterDecision.accepted(
                nextRevision,
                nextRecord,
                receipt,
                List.of(event),
                emissions(command, nextState, event, receipt));
    }

    private static QueueRosterState nextState(
            QueueRosterControlCommand<? extends QueueRosterCommand> command,
            QueueRosterState currentState) {
        QueueRosterCommand payload = command.envelope().payload();
        if (payload instanceof SubmitQueueIntent submit) {
            return currentState.withQueueIntent(QueueIntentSnapshot.from(submit));
        }
        if (payload instanceof CancelQueueIntent cancel) {
            QueueIntentSnapshot snapshot = currentState.queueIntent(cancel.queueIntentId()).orElseThrow();
            return currentState.withQueueIntent(snapshot.cancel(cancel.cancelledAt()));
        }
        if (payload instanceof ExpireQueueIntent expire) {
            QueueIntentSnapshot snapshot = currentState.queueIntent(expire.queueIntentId()).orElseThrow();
            return currentState.withQueueIntent(snapshot.expire(expire.expiredAt()));
        }
        if (payload instanceof FormRosterIntent form) {
            List<QueueIntentSnapshot> selected = currentState.selectedQueueIntents(form);
            RosterIntentSnapshot roster = RosterIntentSnapshot.from(form, selected);
            QueueRosterState next = currentState.withRosterIntent(roster);
            for (QueueIntentSnapshot snapshot : selected) {
                next = next.withQueueIntent(snapshot.markRostered(form.rosterIntentId(), form.formedAt()));
            }
            return next;
        }
        throw new IllegalArgumentException("unknown QueueRoster command");
    }

    private static List<QueueRosterControlEmission> emissions(
            QueueRosterControlCommand<? extends QueueRosterCommand> command,
            QueueRosterState state,
            QueueRosterEvent event,
            QueueRosterReceipt receipt) {
        List<QueueRosterControlEmission> emissions = new java.util.ArrayList<>(List.of(
                new QueueRosterControlEmission(QueueRosterControlEmissionKind.EVENT, event.eventKey(), event.wireValue()),
                new QueueRosterControlEmission(
                        QueueRosterControlEmissionKind.STATE,
                        ControlQueueNames.stateKey(command.envelope().payload().partitionKey()),
                        state.wireValue(command.envelope().payload().partitionKey(), event.revision(), event.traceEnvelope())),
                new QueueRosterControlEmission(QueueRosterControlEmissionKind.RESPONSE, command.envelope().commandId().value(), receipt.wireValue())));
        if (command.envelope().payload() instanceof FormRosterIntent form) {
            RosterIntentSnapshot roster = state.rosterIntent(form.rosterIntentId()).orElseThrow();
            emissions.add(new QueueRosterControlEmission(
                    QueueRosterControlEmissionKind.ROSTER_READY,
                    roster.rosterIntentId().value(),
                    roster.wireValue(event.revision())));
        }
        return List.copyOf(emissions);
    }
}

record StoredQueueRosterDecision(String payloadFingerprint, QueueRosterDecision decision) {
    StoredQueueRosterDecision {
        payloadFingerprint = ControlQueueStrings.requireNonBlank(payloadFingerprint, "payloadFingerprint");
        decision = Objects.requireNonNull(decision, "decision");
    }
}
