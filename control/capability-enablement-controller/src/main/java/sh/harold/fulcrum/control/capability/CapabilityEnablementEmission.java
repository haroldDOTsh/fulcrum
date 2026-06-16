package sh.harold.fulcrum.control.capability;

import java.util.Objects;

public record CapabilityEnablementEmission(
        CapabilityEnablementEmissionKind kind,
        String key,
        String payload) {
    public CapabilityEnablementEmission {
        kind = Objects.requireNonNull(kind, "kind");
        key = ControlCapabilityStrings.requireNonBlank(key, "key");
        payload = Objects.requireNonNull(payload, "payload");
    }
}
