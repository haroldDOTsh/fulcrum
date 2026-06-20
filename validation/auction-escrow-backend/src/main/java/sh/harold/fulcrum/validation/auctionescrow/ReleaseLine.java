package sh.harold.fulcrum.validation.auctionescrow;

import java.util.Objects;

public record ReleaseLine(
        ReleaseLineKind kind,
        String recipientId,
        long amountMinor,
        String currency,
        long sourceHoldSequence) {
    public ReleaseLine {
        kind = Objects.requireNonNull(kind, "kind");
        recipientId = EscrowNames.requireNonBlank(recipientId, "recipientId");
        if (amountMinor < 0) {
            throw new IllegalArgumentException("amountMinor must be non-negative");
        }
        currency = EscrowNames.requireNonBlank(currency, "currency");
        if (sourceHoldSequence <= 0) {
            throw new IllegalArgumentException("sourceHoldSequence must be positive");
        }
    }

    String wireValue() {
        return kind + ":" + recipientId + ":" + amountMinor + ":" + currency + ":" + sourceHoldSequence;
    }
}
