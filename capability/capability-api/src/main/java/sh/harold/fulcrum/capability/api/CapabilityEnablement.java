package sh.harold.fulcrum.capability.api;

import sh.harold.fulcrum.api.kernel.CapabilityId;

import java.util.Objects;

public record CapabilityEnablement(
        CapabilityId capabilityId,
        CapabilityVersion version,
        CapabilityScope scope) {
    public CapabilityEnablement {
        capabilityId = Objects.requireNonNull(capabilityId, "capabilityId");
        version = Objects.requireNonNull(version, "version");
        scope = Objects.requireNonNull(scope, "scope");
    }
}
