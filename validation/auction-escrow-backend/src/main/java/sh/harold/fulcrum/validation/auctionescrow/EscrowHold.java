package sh.harold.fulcrum.validation.auctionescrow;

import java.time.Instant;
import java.util.Objects;

public record EscrowHold(
        long sequence,
        String bidderId,
        long amountMinor,
        String currency,
        Instant heldAt) {
    public EscrowHold {
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        bidderId = EscrowNames.requireNonBlank(bidderId, "bidderId");
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be positive");
        }
        currency = EscrowNames.requireNonBlank(currency, "currency");
        heldAt = Objects.requireNonNull(heldAt, "heldAt");
    }

    String wireValue() {
        return sequence + ":" + bidderId + ":" + amountMinor + ":" + currency;
    }
}
