package sh.harold.fulcrum.control.fault;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;

import java.util.Objects;
import java.util.Optional;

public record FaultReceipt(
        boolean accepted,
        Optional<FaultRecord> faultRecord,
        Revision revision,
        long fencingEpoch,
        String idempotencyKey,
        String commandId,
        Optional<FaultRejectionReason> rejectionReason,
        TraceEnvelope traceEnvelope) {
    public FaultReceipt {
        faultRecord = faultRecord == null ? Optional.empty() : faultRecord;
        revision = Objects.requireNonNull(revision, "revision");
        idempotencyKey = ControlFaultStrings.requireNonBlank(idempotencyKey, "idempotencyKey");
        commandId = ControlFaultStrings.requireNonBlank(commandId, "commandId");
        rejectionReason = rejectionReason == null ? Optional.empty() : rejectionReason;
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }

    public static FaultReceipt accepted(
            FaultRecord faultRecord,
            Revision revision,
            long fencingEpoch,
            String idempotencyKey,
            String commandId) {
        return new FaultReceipt(
                true,
                Optional.of(faultRecord),
                revision,
                fencingEpoch,
                idempotencyKey,
                commandId,
                Optional.empty(),
                faultRecord.traceEnvelope());
    }

    public static FaultReceipt rejected(
            FaultRejectionReason reason,
            Revision revision,
            long fencingEpoch,
            String idempotencyKey,
            String commandId,
            TraceEnvelope traceEnvelope) {
        return new FaultReceipt(
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
