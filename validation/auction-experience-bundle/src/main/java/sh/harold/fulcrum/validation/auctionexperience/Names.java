package sh.harold.fulcrum.validation.auctionexperience;

import java.util.Objects;

final class Names {
    private Names() {
    }

    static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
