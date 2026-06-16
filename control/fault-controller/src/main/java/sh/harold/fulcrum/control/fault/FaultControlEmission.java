package sh.harold.fulcrum.control.fault;

import java.util.Objects;

public record FaultControlEmission(
        FaultControlEmissionKind kind,
        String key,
        String value) {
    public FaultControlEmission {
        kind = Objects.requireNonNull(kind, "kind");
        key = ControlFaultStrings.requireNonBlank(key, "key");
        value = ControlFaultStrings.requireNonBlank(value, "value");
    }
}
