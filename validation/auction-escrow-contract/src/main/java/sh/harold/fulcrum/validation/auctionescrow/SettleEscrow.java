package sh.harold.fulcrum.validation.auctionescrow;

import java.time.Instant;
import java.util.Objects;

public record SettleEscrow(String auctionId, Instant settledAt) implements AuctionEscrowCommand {
    public SettleEscrow {
        auctionId = EscrowNames.requireNonBlank(auctionId, "auctionId");
        settledAt = Objects.requireNonNull(settledAt, "settledAt");
    }
}
