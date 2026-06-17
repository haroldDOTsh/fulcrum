package sh.harold.fulcrum.distribution.launcher;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class RuntimeEnvironment {
    private final Map<String, String> values;

    private RuntimeEnvironment(Map<String, String> values) {
        this.values = Map.copyOf(Objects.requireNonNull(values, "values"));
    }

    static RuntimeEnvironment system() {
        return new RuntimeEnvironment(System.getenv());
    }

    static RuntimeEnvironment of(Map<String, String> values) {
        return new RuntimeEnvironment(values);
    }

    boolean contains(String name) {
        return value(name).isPresent();
    }

    Optional<String> value(String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }
}
