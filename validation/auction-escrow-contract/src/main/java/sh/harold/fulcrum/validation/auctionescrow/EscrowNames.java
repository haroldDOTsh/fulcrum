package sh.harold.fulcrum.validation.auctionescrow;

import java.util.Objects;

public final class EscrowNames {
    private EscrowNames() {
    }

    public static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
