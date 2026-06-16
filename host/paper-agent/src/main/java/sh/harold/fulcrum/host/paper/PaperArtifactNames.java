package sh.harold.fulcrum.host.paper;

import java.util.Objects;

final class PaperArtifactNames {
    private PaperArtifactNames() {
    }

    static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
