package sh.harold.fulcrum.control.capability;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityScope;

import java.time.Instant;
import java.util.Objects;

public record EnableCapability(
        CapabilityScope scope,
        CapabilityId capabilityId,
        String contractSet,
        String reason,
        Instant enabledAt,
        TraceEnvelope traceEnvelope) implements CapabilityEnablementCommand {
    public EnableCapability {
        scope = Objects.requireNonNull(scope, "scope");
        capabilityId = Objects.requireNonNull(capabilityId, "capabilityId");
        contractSet = ControlCapabilityStrings.requireNonBlank(contractSet, "contractSet");
        reason = ControlCapabilityStrings.requireNonBlank(reason, "reason");
        enabledAt = Objects.requireNonNull(enabledAt, "enabledAt");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }
}
