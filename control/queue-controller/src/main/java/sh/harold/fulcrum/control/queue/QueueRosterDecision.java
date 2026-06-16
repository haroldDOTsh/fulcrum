package sh.harold.fulcrum.control.queue;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;

import java.util.List;
import java.util.Objects;

public record QueueRosterDecision(
        QueueRosterDecisionStatus status,
        Revision revision,
        QueueRosterControlRecord record,
        QueueRosterReceipt receipt,
        List<QueueRosterEvent> events,
        List<QueueRosterControlEmission> emissions,
        TraceEnvelope traceEnvelope) {
    public QueueRosterDecision {
        status = Objects.requireNonNull(status, "status");
        revision = Objects.requireNonNull(revision, "revision");
        record = Objects.requireNonNull(record, "record");
        receipt = Objects.requireNonNull(receipt, "receipt");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        emissions = List.copyOf(Objects.requireNonNull(emissions, "emissions"));
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }

    public static QueueRosterDecision accepted(
            Revision revision,
            QueueRosterControlRecord record,
            QueueRosterReceipt receipt,
            List<QueueRosterEvent> events,
            List<QueueRosterControlEmission> emissions) {
        return new QueueRosterDecision(QueueRosterDecisionStatus.ACCEPTED, revision, record, receipt, events, emissions, receipt.traceEnvelope());
    }

    public static QueueRosterDecision rejected(
            QueueRosterRejectionReason reason,
            Revision revision,
            QueueRosterControlRecord record,
            TraceEnvelope traceEnvelope,
            long fencingEpoch,
            String idempotencyKey,
            String commandId) {
        return new QueueRosterDecision(
                QueueRosterDecisionStatus.REJECTED,
                revision,
                record,
                QueueRosterReceipt.rejected(reason, revision, fencingEpoch, idempotencyKey, commandId, traceEnvelope),
                List.of(),
                List.of(),
                traceEnvelope);
    }

    public QueueRosterDecision asReplay() {
        return new QueueRosterDecision(QueueRosterDecisionStatus.REPLAYED, revision, record, receipt, List.of(), List.of(), traceEnvelope);
    }
}
