package sh.harold.fulcrum.control.queue;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;

import java.util.Objects;
import java.util.Optional;

public record QueueRosterReceipt(
        boolean accepted,
        Optional<QueueRosterState> state,
        Revision revision,
        long fencingEpoch,
        String idempotencyKey,
        String commandId,
        Optional<QueueRosterRejectionReason> rejectionReason,
        TraceEnvelope traceEnvelope) {
    public QueueRosterReceipt {
        state = state == null ? Optional.empty() : state;
        revision = Objects.requireNonNull(revision, "revision");
        idempotencyKey = ControlQueueStrings.requireNonBlank(idempotencyKey, "idempotencyKey");
        commandId = ControlQueueStrings.requireNonBlank(commandId, "commandId");
        rejectionReason = rejectionReason == null ? Optional.empty() : rejectionReason;
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }

    public static QueueRosterReceipt accepted(
            QueueRosterState state,
            Revision revision,
            long fencingEpoch,
            String idempotencyKey,
            String commandId,
            TraceEnvelope traceEnvelope) {
        return new QueueRosterReceipt(
                true,
                Optional.of(state),
                revision,
                fencingEpoch,
                idempotencyKey,
                commandId,
                Optional.empty(),
                traceEnvelope);
    }

    public static QueueRosterReceipt rejected(
            QueueRosterRejectionReason reason,
            Revision revision,
            long fencingEpoch,
            String idempotencyKey,
            String commandId,
            TraceEnvelope traceEnvelope) {
        return new QueueRosterReceipt(
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
