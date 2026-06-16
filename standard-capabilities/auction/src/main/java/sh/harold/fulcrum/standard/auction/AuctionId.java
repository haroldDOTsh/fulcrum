package sh.harold.fulcrum.standard.auction;

import java.util.Objects;

public record AuctionId(String value) {
    public AuctionId {
        value = requireNonBlank(value, "auctionId");
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
