package sh.harold.fulcrum.capability.api;

import java.util.Objects;

final class CapabilityNames {
    private CapabilityNames() {
    }

    static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
