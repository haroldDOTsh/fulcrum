package sh.harold.fulcrum.data.contract;

import java.util.Objects;

final class DeclarationNames {
    private DeclarationNames() {
    }

    static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
