package sh.harold.fulcrum.api.kernel;

import java.util.Objects;
import java.util.UUID;

final class Ids {
    private Ids() {
    }

    static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    static UUID requireUuid(UUID value, String label) {
        return Objects.requireNonNull(value, label);
    }
}
