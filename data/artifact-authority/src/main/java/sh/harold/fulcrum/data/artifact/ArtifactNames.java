package sh.harold.fulcrum.data.artifact;

import java.util.Objects;

final class ArtifactNames {
    private ArtifactNames() {
    }

    static String requireNonBlank(String value) {
        String checked = Objects.requireNonNull(value, "value").trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        return checked;
    }
}
