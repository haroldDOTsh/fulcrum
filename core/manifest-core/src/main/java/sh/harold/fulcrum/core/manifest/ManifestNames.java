package sh.harold.fulcrum.core.manifest;

import java.util.Objects;

final class ManifestNames {
    private ManifestNames() {
    }

    static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
