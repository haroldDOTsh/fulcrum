package sh.harold.fulcrum.control.allocation;

import java.util.Objects;

public record RosterAllocationEmission(
        RosterAllocationEmissionKind kind,
        String key,
        String value) {
    public RosterAllocationEmission {
        kind = Objects.requireNonNull(kind, "kind");
        key = ControlAllocationStrings.requireNonBlank(key, "key");
        value = ControlAllocationStrings.requireNonBlank(value, "value");
    }
}
