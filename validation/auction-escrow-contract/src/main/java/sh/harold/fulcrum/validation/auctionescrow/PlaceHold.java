package sh.harold.fulcrum.validation.auctionescrow;

import java.time.Instant;
import java.util.Objects;

public record PlaceHold(
        String auctionId,
        String bidderId,
        long amountMinor,
        String currency,
        Instant heldAt) implements AuctionEscrowCommand {
    public PlaceHold {
        auctionId = EscrowNames.requireNonBlank(auctionId, "auctionId");
        bidderId = EscrowNames.requireNonBlank(bidderId, "bidderId");
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be positive");
        }
        currency = EscrowNames.requireNonBlank(currency, "currency");
        heldAt = Objects.requireNonNull(heldAt, "heldAt");
    }
}
