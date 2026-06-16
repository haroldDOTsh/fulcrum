package sh.harold.fulcrum.control.fault;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;

import java.util.List;
import java.util.Objects;

public record FaultDecision(
        FaultDecisionStatus status,
        Revision revision,
        FaultControlRecord record,
        FaultReceipt receipt,
        List<FaultEvent> events,
        List<FaultControlEmission> emissions,
        TraceEnvelope traceEnvelope) {
    public FaultDecision {
        status = Objects.requireNonNull(status, "status");
        revision = Objects.requireNonNull(revision, "revision");
        record = Objects.requireNonNull(record, "record");
        receipt = Objects.requireNonNull(receipt, "receipt");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        emissions = List.copyOf(Objects.requireNonNull(emissions, "emissions"));
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }

    public static FaultDecision accepted(
            Revision revision,
            FaultControlRecord record,
            FaultReceipt receipt,
            List<FaultEvent> events,
            List<FaultControlEmission> emissions) {
        return new FaultDecision(FaultDecisionStatus.ACCEPTED, revision, record, receipt, events, emissions, receipt.traceEnvelope());
    }

    public static FaultDecision rejected(
            FaultRejectionReason reason,
            Revision revision,
            FaultControlRecord record,
            TraceEnvelope traceEnvelope,
            long fencingEpoch,
            String idempotencyKey,
            String commandId) {
        return new FaultDecision(
                FaultDecisionStatus.REJECTED,
                revision,
                record,
                FaultReceipt.rejected(reason, revision, fencingEpoch, idempotencyKey, commandId, traceEnvelope),
                List.of(),
                List.of(),
                traceEnvelope);
    }

    public FaultDecision asReplay() {
        return new FaultDecision(FaultDecisionStatus.REPLAYED, revision, record, receipt, List.of(), List.of(), traceEnvelope);
    }
}
