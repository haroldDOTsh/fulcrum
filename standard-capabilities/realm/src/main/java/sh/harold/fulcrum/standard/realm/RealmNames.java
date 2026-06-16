package sh.harold.fulcrum.standard.realm;

import java.util.Objects;

final class RealmNames {
    private RealmNames() {
    }

    static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
