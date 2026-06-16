package sh.harold.fulcrum.control.capability;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;

import java.util.List;
import java.util.Objects;

public record CapabilityEnablementDecision(
        CapabilityEnablementDecisionStatus status,
        Revision revision,
        CapabilityEnablementControlRecord record,
        CapabilityEnablementReceipt receipt,
        List<CapabilityEnablementEvent> events,
        List<CapabilityEnablementEmission> emissions,
        TraceEnvelope traceEnvelope) {
    public CapabilityEnablementDecision {
        status = Objects.requireNonNull(status, "status");
        revision = Objects.requireNonNull(revision, "revision");
        record = Objects.requireNonNull(record, "record");
        receipt = Objects.requireNonNull(receipt, "receipt");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        emissions = List.copyOf(Objects.requireNonNull(emissions, "emissions"));
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }

    public static CapabilityEnablementDecision accepted(
            Revision revision,
            CapabilityEnablementControlRecord record,
            CapabilityEnablementReceipt receipt,
            List<CapabilityEnablementEvent> events,
            List<CapabilityEnablementEmission> emissions) {
        return new CapabilityEnablementDecision(
                CapabilityEnablementDecisionStatus.ACCEPTED,
                revision,
                record,
                receipt,
                events,
                emissions,
                receipt.traceEnvelope());
    }

    public static CapabilityEnablementDecision rejected(
            CapabilityEnablementRejectionReason reason,
            Revision revision,
            CapabilityEnablementControlRecord record,
            TraceEnvelope traceEnvelope,
            long fencingEpoch,
            String idempotencyKey,
            String commandId) {
        return new CapabilityEnablementDecision(
                CapabilityEnablementDecisionStatus.REJECTED,
                revision,
                record,
                CapabilityEnablementReceipt.rejected(reason, record.state().scope(), revision, fencingEpoch, idempotencyKey, commandId, traceEnvelope),
                List.of(),
                List.of(),
                traceEnvelope);
    }

    public CapabilityEnablementDecision asReplay() {
        return new CapabilityEnablementDecision(
                CapabilityEnablementDecisionStatus.REPLAYED,
                revision,
                record,
                receipt,
                List.of(),
                List.of(),
                traceEnvelope);
    }
}
