package sh.harold.fulcrum.control.capability;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.CapabilityId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record CapabilityBinding(
        CapabilityId capabilityId,
        boolean enabled,
        String contractSet,
        Optional<String> reason,
        Instant changedAt,
        TraceEnvelope traceEnvelope) {
    public CapabilityBinding {
        capabilityId = Objects.requireNonNull(capabilityId, "capabilityId");
        contractSet = ControlCapabilityStrings.requireNonBlank(contractSet, "contractSet");
        reason = reason == null
                ? Optional.empty()
                : reason.map(value -> ControlCapabilityStrings.requireNonBlank(value, "reason"));
        changedAt = Objects.requireNonNull(changedAt, "changedAt");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }

    public static CapabilityBinding enabled(EnableCapability command) {
        return new CapabilityBinding(
                command.capabilityId(),
                true,
                command.contractSet(),
                Optional.of(command.reason()),
                command.enabledAt(),
                command.traceEnvelope());
    }

    public CapabilityBinding disabled(DisableCapability command) {
        return new CapabilityBinding(
                capabilityId,
                false,
                contractSet,
                Optional.of(command.reason()),
                command.disabledAt(),
                command.traceEnvelope());
    }

    public String wireValue() {
        return capabilityId.value()
                + ":enabled=" + enabled
                + ":contractSet=" + contractSet
                + ":reason=" + reason.orElse("none")
                + ":changedAt=" + changedAt;
    }
}
