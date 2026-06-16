package sh.harold.fulcrum.control.lifecycle;

import java.util.Objects;

public record ExperienceSessionEmission(
        ExperienceSessionEmissionKind kind,
        String key,
        String value) {
    public ExperienceSessionEmission {
        kind = Objects.requireNonNull(kind, "kind");
        key = ControlLifecycleStrings.requireNonBlank(key, "key");
        value = ControlLifecycleStrings.requireNonBlank(value, "value");
    }
}
