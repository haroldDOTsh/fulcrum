package sh.harold.fulcrum.control.instance;

import java.util.Objects;

public record InstanceRegistryEmission(
        InstanceRegistryEmissionKind kind,
        String key,
        String payload) {
    public InstanceRegistryEmission {
        kind = Objects.requireNonNull(kind, "kind");
        key = ControlInstanceStrings.requireNonBlank(key, "key");
        payload = Objects.requireNonNull(payload, "payload");
    }
}
