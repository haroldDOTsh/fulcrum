package sh.harold.fulcrum.validation.auctionescrow;

import java.time.Instant;
import java.util.Objects;

public record CancelEscrow(String auctionId, String reason, Instant cancelledAt) implements AuctionEscrowCommand {
    public CancelEscrow {
        auctionId = EscrowNames.requireNonBlank(auctionId, "auctionId");
        reason = EscrowNames.requireNonBlank(reason, "reason");
        cancelledAt = Objects.requireNonNull(cancelledAt, "cancelledAt");
    }
}
