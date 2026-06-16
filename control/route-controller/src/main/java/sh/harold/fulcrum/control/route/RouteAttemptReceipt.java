package sh.harold.fulcrum.control.route;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;

import java.util.Objects;
import java.util.Optional;

public record RouteAttemptReceipt(
        boolean accepted,
        Optional<RouteAttemptSnapshot> snapshot,
        Revision revision,
        long fencingEpoch,
        String idempotencyKey,
        String commandId,
        Optional<RouteAttemptRejectionReason> rejectionReason,
        TraceEnvelope traceEnvelope) {
    public RouteAttemptReceipt {
        snapshot = snapshot == null ? Optional.empty() : snapshot;
        revision = Objects.requireNonNull(revision, "revision");
        idempotencyKey = ControlRouteStrings.requireNonBlank(idempotencyKey, "idempotencyKey");
        commandId = ControlRouteStrings.requireNonBlank(commandId, "commandId");
        rejectionReason = rejectionReason == null ? Optional.empty() : rejectionReason;
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }

    public static RouteAttemptReceipt accepted(
            RouteAttemptSnapshot snapshot,
            Revision revision,
            long fencingEpoch,
            String idempotencyKey,
            String commandId) {
        return new RouteAttemptReceipt(
                true,
                Optional.of(snapshot),
                revision,
                fencingEpoch,
                idempotencyKey,
                commandId,
                Optional.empty(),
                snapshot.traceEnvelope());
    }

    public static RouteAttemptReceipt rejected(
            RouteAttemptRejectionReason reason,
            Revision revision,
            long fencingEpoch,
            String idempotencyKey,
            String commandId,
            TraceEnvelope traceEnvelope) {
        return new RouteAttemptReceipt(
                false,
                Optional.empty(),
                revision,
                fencingEpoch,
                idempotencyKey,
                commandId,
                Optional.of(reason),
                traceEnvelope);
    }

    public String wireValue() {
        return "accepted=" + accepted
                + "|revision=" + revision.value()
                + "|commandId=" + commandId
                + "|reason=" + rejectionReason.map(Enum::name).orElse("none")
                + "|traceId=" + traceEnvelope.traceId();
    }
}
