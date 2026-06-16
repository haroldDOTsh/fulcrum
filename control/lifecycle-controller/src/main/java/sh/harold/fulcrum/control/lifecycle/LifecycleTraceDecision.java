package sh.harold.fulcrum.control.lifecycle;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;

import java.util.List;
import java.util.Objects;

public record LifecycleTraceDecision(
        LifecycleTraceDecisionStatus status,
        Revision revision,
        LifecycleTraceControlRecord record,
        LifecycleTraceReceipt receipt,
        List<LifecycleTraceEvent> events,
        List<LifecycleTraceEmission> emissions,
        TraceEnvelope traceEnvelope) {
    public LifecycleTraceDecision {
        status = Objects.requireNonNull(status, "status");
        revision = Objects.requireNonNull(revision, "revision");
        record = Objects.requireNonNull(record, "record");
        receipt = Objects.requireNonNull(receipt, "receipt");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        emissions = List.copyOf(Objects.requireNonNull(emissions, "emissions"));
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }

    public static LifecycleTraceDecision accepted(
            Revision revision,
            LifecycleTraceControlRecord record,
            LifecycleTraceReceipt receipt,
            List<LifecycleTraceEvent> events,
            List<LifecycleTraceEmission> emissions) {
        return new LifecycleTraceDecision(LifecycleTraceDecisionStatus.ACCEPTED, revision, record, receipt, events, emissions, receipt.traceEnvelope());
    }

    public static LifecycleTraceDecision rejected(
            LifecycleTraceRejectionReason reason,
            Revision revision,
            LifecycleTraceControlRecord record,
            TraceEnvelope traceEnvelope,
            long fencingEpoch,
            String idempotencyKey,
            String commandId) {
        return new LifecycleTraceDecision(
                LifecycleTraceDecisionStatus.REJECTED,
                revision,
                record,
                LifecycleTraceReceipt.rejected(reason, record.traceRecord(), revision, fencingEpoch, idempotencyKey, commandId, traceEnvelope),
                List.of(),
                List.of(),
                traceEnvelope);
    }

    public LifecycleTraceDecision asReplay() {
        return new LifecycleTraceDecision(LifecycleTraceDecisionStatus.REPLAYED, revision, record, receipt, List.of(), List.of(), traceEnvelope);
    }
}
