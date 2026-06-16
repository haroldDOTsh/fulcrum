package sh.harold.fulcrum.control.route;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;

import java.util.List;
import java.util.Objects;

public record RouteAttemptDecision(
        RouteAttemptDecisionStatus status,
        Revision revision,
        RouteAttemptControlRecord record,
        RouteAttemptReceipt receipt,
        List<RouteAttemptEvent> events,
        List<RouteAttemptControlEmission> emissions,
        TraceEnvelope traceEnvelope) {
    public RouteAttemptDecision {
        status = Objects.requireNonNull(status, "status");
        revision = Objects.requireNonNull(revision, "revision");
        record = Objects.requireNonNull(record, "record");
        receipt = Objects.requireNonNull(receipt, "receipt");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        emissions = List.copyOf(Objects.requireNonNull(emissions, "emissions"));
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }

    public static RouteAttemptDecision accepted(
            Revision revision,
            RouteAttemptControlRecord record,
            RouteAttemptReceipt receipt,
            List<RouteAttemptEvent> events,
            List<RouteAttemptControlEmission> emissions) {
        return new RouteAttemptDecision(RouteAttemptDecisionStatus.ACCEPTED, revision, record, receipt, events, emissions, receipt.traceEnvelope());
    }

    public static RouteAttemptDecision rejected(
            RouteAttemptRejectionReason reason,
            Revision revision,
            RouteAttemptControlRecord record,
            TraceEnvelope traceEnvelope,
            long fencingEpoch,
            String idempotencyKey,
            String commandId) {
        return new RouteAttemptDecision(
                RouteAttemptDecisionStatus.REJECTED,
                revision,
                record,
                RouteAttemptReceipt.rejected(reason, revision, fencingEpoch, idempotencyKey, commandId, traceEnvelope),
                List.of(),
                List.of(),
                traceEnvelope);
    }

    public RouteAttemptDecision asReplay() {
        return new RouteAttemptDecision(RouteAttemptDecisionStatus.REPLAYED, revision, record, receipt, List.of(), List.of(), traceEnvelope);
    }
}
