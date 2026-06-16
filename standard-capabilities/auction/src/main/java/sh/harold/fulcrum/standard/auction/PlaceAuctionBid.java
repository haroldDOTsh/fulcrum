package sh.harold.fulcrum.standard.auction;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

public record PlaceAuctionBid(
        AuctionId auctionId,
        SubjectId bidderSubjectId,
        long bidMinorUnits,
        String currencyKey,
        Instant placedAt,
        long expectedRevision) implements AuctionCommand {
    public PlaceAuctionBid {
        auctionId = Objects.requireNonNull(auctionId, "auctionId");
        bidderSubjectId = Objects.requireNonNull(bidderSubjectId, "bidderSubjectId");
        currencyKey = requireNonBlank(currencyKey, "currencyKey").toLowerCase(Locale.ROOT);
        placedAt = Objects.requireNonNull(placedAt, "placedAt");
        if (bidMinorUnits <= 0) {
            throw new IllegalArgumentException("bidMinorUnits must be positive");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
