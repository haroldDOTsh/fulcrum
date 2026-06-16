package sh.harold.fulcrum.control.capability;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.capability.api.CapabilityScope;

import java.util.Objects;
import java.util.Optional;

public record CapabilityEnablementReceipt(
        boolean accepted,
        CapabilityScope scope,
        Optional<CapabilityBinding> binding,
        Revision revision,
        long fencingEpoch,
        String idempotencyKey,
        String commandId,
        Optional<CapabilityEnablementRejectionReason> rejectionReason,
        TraceEnvelope traceEnvelope) {
    public CapabilityEnablementReceipt {
        scope = Objects.requireNonNull(scope, "scope");
        binding = binding == null ? Optional.empty() : binding;
        revision = Objects.requireNonNull(revision, "revision");
        if (fencingEpoch < 0) {
            throw new IllegalArgumentException("fencingEpoch must be non-negative");
        }
        idempotencyKey = ControlCapabilityStrings.requireNonBlank(idempotencyKey, "idempotencyKey");
        commandId = ControlCapabilityStrings.requireNonBlank(commandId, "commandId");
        rejectionReason = rejectionReason == null ? Optional.empty() : rejectionReason;
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }

    public static CapabilityEnablementReceipt accepted(
            CapabilityScope scope,
            CapabilityBinding binding,
            Revision revision,
            long fencingEpoch,
            String idempotencyKey,
            String commandId) {
        return new CapabilityEnablementReceipt(
                true,
                scope,
                Optional.of(binding),
                revision,
                fencingEpoch,
                idempotencyKey,
                commandId,
                Optional.empty(),
                binding.traceEnvelope());
    }

    public static CapabilityEnablementReceipt rejected(
            CapabilityEnablementRejectionReason reason,
            CapabilityScope scope,
            Revision revision,
            long fencingEpoch,
            String idempotencyKey,
            String commandId,
            TraceEnvelope traceEnvelope) {
        return new CapabilityEnablementReceipt(
                false,
                scope,
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
                + "|scope=" + scope.value()
                + "|revision=" + revision.value()
                + "|commandId=" + commandId
                + "|reason=" + rejectionReason.map(Enum::name).orElse("none")
                + "|traceId=" + traceEnvelope.traceId();
    }
}
