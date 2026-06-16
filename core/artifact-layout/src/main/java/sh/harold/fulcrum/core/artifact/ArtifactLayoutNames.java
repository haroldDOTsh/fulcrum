package sh.harold.fulcrum.core.artifact;

import java.util.Objects;

final class ArtifactLayoutNames {
    private ArtifactLayoutNames() {
    }

    static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
