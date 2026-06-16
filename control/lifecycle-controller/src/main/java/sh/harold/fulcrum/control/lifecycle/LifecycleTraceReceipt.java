package sh.harold.fulcrum.control.lifecycle;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;

import java.util.Objects;
import java.util.Optional;

public record LifecycleTraceReceipt(
        boolean accepted,
        LifecycleTraceRecord traceRecord,
        Revision revision,
        long fencingEpoch,
        String idempotencyKey,
        String commandId,
        Optional<LifecycleTraceRejectionReason> rejectionReason,
        TraceEnvelope traceEnvelope) {
    public LifecycleTraceReceipt {
        traceRecord = Objects.requireNonNull(traceRecord, "traceRecord");
        revision = Objects.requireNonNull(revision, "revision");
        idempotencyKey = ControlLifecycleStrings.requireNonBlank(idempotencyKey, "idempotencyKey");
        commandId = ControlLifecycleStrings.requireNonBlank(commandId, "commandId");
        rejectionReason = rejectionReason == null ? Optional.empty() : rejectionReason;
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }

    public static LifecycleTraceReceipt accepted(
            LifecycleTraceRecord traceRecord,
            Revision revision,
            long fencingEpoch,
            String idempotencyKey,
            String commandId,
            TraceEnvelope traceEnvelope) {
        return new LifecycleTraceReceipt(
                true,
                traceRecord,
                revision,
                fencingEpoch,
                idempotencyKey,
                commandId,
                Optional.empty(),
                traceEnvelope);
    }

    public static LifecycleTraceReceipt rejected(
            LifecycleTraceRejectionReason reason,
            LifecycleTraceRecord traceRecord,
            Revision revision,
            long fencingEpoch,
            String idempotencyKey,
            String commandId,
            TraceEnvelope traceEnvelope) {
        return new LifecycleTraceReceipt(
                false,
                traceRecord,
                revision,
                fencingEpoch,
                idempotencyKey,
                commandId,
                Optional.of(reason),
                traceEnvelope);
    }

    public String wireValue() {
        return "accepted=" + accepted
                + "|traceId=" + traceRecord.traceId().value()
                + "|revision=" + revision.value()
                + "|commandId=" + commandId
                + "|reason=" + rejectionReason.map(Enum::name).orElse("none");
    }
}
