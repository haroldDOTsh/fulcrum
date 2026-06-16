package sh.harold.fulcrum.capability.api;

import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.PoolId;

public record CapabilityScope(String value) {
    public static final CapabilityScope NETWORK = new CapabilityScope("network");

    public CapabilityScope {
        value = CapabilityNames.requireNonBlank(value, "scope");
        scopeType(value);
    }

    public static CapabilityScope pool(PoolId poolId) {
        return new CapabilityScope("pool:" + poolId.value());
    }

    public static CapabilityScope experience(ExperienceId experienceId) {
        return new CapabilityScope("experience:" + experienceId.value());
    }

    public static CapabilityScope mode(String modeId) {
        return new CapabilityScope("mode:" + CapabilityNames.requireNonBlank(modeId, "modeId"));
    }

    public CapabilityScopeType type() {
        return scopeType(value);
    }

    public boolean permits(CapabilityScope requestedScope) {
        CapabilityScope requested = java.util.Objects.requireNonNull(requestedScope, "requestedScope");
        return this.equals(NETWORK) || this.equals(requested);
    }

    private static CapabilityScopeType scopeType(String value) {
        if ("network".equals(value)) {
            return CapabilityScopeType.NETWORK;
        }
        if (value.startsWith("pool:") && value.length() > "pool:".length()) {
            return CapabilityScopeType.POOL;
        }
        if (value.startsWith("experience:") && value.length() > "experience:".length()) {
            return CapabilityScopeType.EXPERIENCE;
        }
        if (value.startsWith("mode:") && value.length() > "mode:".length()) {
            return CapabilityScopeType.MODE;
        }
        throw new IllegalArgumentException("unsupported capability scope: " + value);
    }
}
