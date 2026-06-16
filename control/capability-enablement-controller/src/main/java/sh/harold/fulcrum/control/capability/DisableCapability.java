package sh.harold.fulcrum.control.capability;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityScope;

import java.time.Instant;
import java.util.Objects;

public record DisableCapability(
        CapabilityScope scope,
        CapabilityId capabilityId,
        String reason,
        Instant disabledAt,
        TraceEnvelope traceEnvelope) implements CapabilityEnablementCommand {
    public DisableCapability {
        scope = Objects.requireNonNull(scope, "scope");
        capabilityId = Objects.requireNonNull(capabilityId, "capabilityId");
        reason = ControlCapabilityStrings.requireNonBlank(reason, "reason");
        disabledAt = Objects.requireNonNull(disabledAt, "disabledAt");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }
}
