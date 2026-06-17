package sh.harold.fulcrum.control.allocation;

import java.util.Objects;

public record SharedShardAllocationEmission(
        SharedShardAllocationEmissionKind kind,
        String key,
        String value) {
    public SharedShardAllocationEmission {
        kind = Objects.requireNonNull(kind, "kind");
        key = ControlAllocationStrings.requireNonBlank(key, "key");
        value = ControlAllocationStrings.requireNonBlank(value, "value");
    }
}
