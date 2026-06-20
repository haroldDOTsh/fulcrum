package sh.harold.fulcrum.validation.auctionescrow;

import java.time.Instant;
import java.util.Objects;

public record OpenEscrow(
        String auctionId,
        String sellerId,
        String itemRef,
        String currency,
        Instant openedAt) implements AuctionEscrowCommand {
    public OpenEscrow {
        auctionId = EscrowNames.requireNonBlank(auctionId, "auctionId");
        sellerId = EscrowNames.requireNonBlank(sellerId, "sellerId");
        itemRef = EscrowNames.requireNonBlank(itemRef, "itemRef");
        currency = EscrowNames.requireNonBlank(currency, "currency");
        openedAt = Objects.requireNonNull(openedAt, "openedAt");
    }
}
