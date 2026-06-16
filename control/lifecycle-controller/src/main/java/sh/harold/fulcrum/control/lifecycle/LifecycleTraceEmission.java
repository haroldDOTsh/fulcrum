package sh.harold.fulcrum.control.lifecycle;

import java.util.Objects;

public record LifecycleTraceEmission(
        LifecycleTraceEmissionKind kind,
        String key,
        String value) {
    public LifecycleTraceEmission {
        kind = Objects.requireNonNull(kind, "kind");
        key = ControlLifecycleStrings.requireNonBlank(key, "key");
        value = ControlLifecycleStrings.requireNonBlank(value, "value");
    }
}
