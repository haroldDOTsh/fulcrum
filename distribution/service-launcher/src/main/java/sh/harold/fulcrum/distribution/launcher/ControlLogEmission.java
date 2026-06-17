package sh.harold.fulcrum.distribution.launcher;

import java.util.Objects;

record ControlLogEmission(
        String kind,
        String key,
        String value) {
    ControlLogEmission {
        kind = requireNonBlank(kind, "kind");
        key = requireNonBlank(key, "key");
        value = Objects.requireNonNull(value, "value");
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
