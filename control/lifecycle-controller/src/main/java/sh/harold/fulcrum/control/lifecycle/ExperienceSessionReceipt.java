package sh.harold.fulcrum.control.lifecycle;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;

import java.util.Objects;
import java.util.Optional;

public record ExperienceSessionReceipt(
        boolean accepted,
        Optional<ExperienceSessionRecord> sessionRecord,
        Revision revision,
        long fencingEpoch,
        String idempotencyKey,
        String commandId,
        Optional<ExperienceSessionRejectionReason> rejectionReason,
        TraceEnvelope traceEnvelope) {
    public ExperienceSessionReceipt {
        sessionRecord = sessionRecord == null ? Optional.empty() : sessionRecord;
        revision = Objects.requireNonNull(revision, "revision");
        idempotencyKey = ControlLifecycleStrings.requireNonBlank(idempotencyKey, "idempotencyKey");
        commandId = ControlLifecycleStrings.requireNonBlank(commandId, "commandId");
        rejectionReason = rejectionReason == null ? Optional.empty() : rejectionReason;
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }

    public static ExperienceSessionReceipt accepted(
            ExperienceSessionRecord sessionRecord,
            Revision revision,
            long fencingEpoch,
            String idempotencyKey,
            String commandId) {
        return new ExperienceSessionReceipt(
                true,
                Optional.of(sessionRecord),
                revision,
                fencingEpoch,
                idempotencyKey,
                commandId,
                Optional.empty(),
                sessionRecord.traceEnvelope());
    }

    public static ExperienceSessionReceipt rejected(
            ExperienceSessionRejectionReason reason,
            Revision revision,
            long fencingEpoch,
            String idempotencyKey,
            String commandId,
            TraceEnvelope traceEnvelope) {
        return new ExperienceSessionReceipt(
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
