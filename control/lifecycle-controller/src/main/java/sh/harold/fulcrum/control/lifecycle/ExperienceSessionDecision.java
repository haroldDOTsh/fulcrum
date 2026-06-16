package sh.harold.fulcrum.control.lifecycle;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;

import java.util.List;
import java.util.Objects;

public record ExperienceSessionDecision(
        ExperienceSessionDecisionStatus status,
        Revision revision,
        ExperienceSessionControlRecord record,
        ExperienceSessionReceipt receipt,
        List<ExperienceSessionEvent> events,
        List<ExperienceSessionEmission> emissions,
        TraceEnvelope traceEnvelope) {
    public ExperienceSessionDecision {
        status = Objects.requireNonNull(status, "status");
        revision = Objects.requireNonNull(revision, "revision");
        record = Objects.requireNonNull(record, "record");
        receipt = Objects.requireNonNull(receipt, "receipt");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        emissions = List.copyOf(Objects.requireNonNull(emissions, "emissions"));
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }

    public static ExperienceSessionDecision accepted(
            Revision revision,
            ExperienceSessionControlRecord record,
            ExperienceSessionReceipt receipt,
            List<ExperienceSessionEvent> events,
            List<ExperienceSessionEmission> emissions) {
        return new ExperienceSessionDecision(ExperienceSessionDecisionStatus.ACCEPTED, revision, record, receipt, events, emissions, receipt.traceEnvelope());
    }

    public static ExperienceSessionDecision rejected(
            ExperienceSessionRejectionReason reason,
            Revision revision,
            ExperienceSessionControlRecord record,
            TraceEnvelope traceEnvelope,
            long fencingEpoch,
            String idempotencyKey,
            String commandId) {
        return new ExperienceSessionDecision(
                ExperienceSessionDecisionStatus.REJECTED,
                revision,
                record,
                ExperienceSessionReceipt.rejected(reason, revision, fencingEpoch, idempotencyKey, commandId, traceEnvelope),
                List.of(),
                List.of(),
                traceEnvelope);
    }

    public ExperienceSessionDecision asReplay() {
        return new ExperienceSessionDecision(ExperienceSessionDecisionStatus.REPLAYED, revision, record, receipt, List.of(), List.of(), traceEnvelope);
    }
}
