package sh.harold.fulcrum.control.instance;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;

import java.util.Objects;
import java.util.Optional;

public record InstanceRegistryReceipt(
        boolean accepted,
        Optional<InstanceSnapshot> snapshot,
        Revision revision,
        long fencingEpoch,
        String idempotencyKey,
        String commandId,
        Optional<InstanceRegistryRejectionReason> rejectionReason,
        TraceEnvelope traceEnvelope) {
    public InstanceRegistryReceipt {
        snapshot = snapshot == null ? Optional.empty() : snapshot;
        revision = Objects.requireNonNull(revision, "revision");
        if (fencingEpoch < 0) {
            throw new IllegalArgumentException("fencingEpoch must be non-negative");
        }
        idempotencyKey = ControlInstanceStrings.requireNonBlank(idempotencyKey, "idempotencyKey");
        commandId = ControlInstanceStrings.requireNonBlank(commandId, "commandId");
        rejectionReason = rejectionReason == null ? Optional.empty() : rejectionReason;
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }

    public static InstanceRegistryReceipt accepted(
            InstanceSnapshot snapshot,
            Revision revision,
            long fencingEpoch,
            String idempotencyKey,
            String commandId) {
        return new InstanceRegistryReceipt(
                true,
                Optional.of(snapshot),
                revision,
                fencingEpoch,
                idempotencyKey,
                commandId,
                Optional.empty(),
                snapshot.traceEnvelope());
    }

    public static InstanceRegistryReceipt rejected(
            InstanceRegistryRejectionReason reason,
            Revision revision,
            long fencingEpoch,
            String idempotencyKey,
            String commandId,
            TraceEnvelope traceEnvelope) {
        return new InstanceRegistryReceipt(
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
