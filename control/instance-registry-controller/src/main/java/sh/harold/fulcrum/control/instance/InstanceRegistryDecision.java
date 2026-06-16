package sh.harold.fulcrum.control.instance;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;

import java.util.List;
import java.util.Objects;

public record InstanceRegistryDecision(
        InstanceRegistryDecisionStatus status,
        Revision revision,
        InstanceRegistryRecord record,
        InstanceRegistryReceipt receipt,
        List<InstanceRegistryEvent> events,
        List<InstanceRegistryEmission> emissions,
        TraceEnvelope traceEnvelope) {
    public InstanceRegistryDecision {
        status = Objects.requireNonNull(status, "status");
        revision = Objects.requireNonNull(revision, "revision");
        record = Objects.requireNonNull(record, "record");
        receipt = Objects.requireNonNull(receipt, "receipt");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        emissions = List.copyOf(Objects.requireNonNull(emissions, "emissions"));
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }

    public static InstanceRegistryDecision accepted(
            Revision revision,
            InstanceRegistryRecord record,
            InstanceRegistryReceipt receipt,
            List<InstanceRegistryEvent> events,
            List<InstanceRegistryEmission> emissions) {
        return new InstanceRegistryDecision(
                InstanceRegistryDecisionStatus.ACCEPTED,
                revision,
                record,
                receipt,
                events,
                emissions,
                receipt.traceEnvelope());
    }

    public static InstanceRegistryDecision rejected(
            InstanceRegistryRejectionReason reason,
            Revision revision,
            InstanceRegistryRecord record,
            TraceEnvelope traceEnvelope,
            long fencingEpoch,
            String idempotencyKey,
            String commandId) {
        return new InstanceRegistryDecision(
                InstanceRegistryDecisionStatus.REJECTED,
                revision,
                record,
                InstanceRegistryReceipt.rejected(reason, revision, fencingEpoch, idempotencyKey, commandId, traceEnvelope),
                List.of(),
                List.of(),
                traceEnvelope);
    }

    public InstanceRegistryDecision asReplay() {
        return new InstanceRegistryDecision(
                InstanceRegistryDecisionStatus.REPLAYED,
                revision,
                record,
                receipt,
                List.of(),
                List.of(),
                traceEnvelope);
    }
}
