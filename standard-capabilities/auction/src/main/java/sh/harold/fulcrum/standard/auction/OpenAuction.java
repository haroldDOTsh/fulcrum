package sh.harold.fulcrum.standard.auction;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

public record OpenAuction(
        AuctionId auctionId,
        SubjectId sellerSubjectId,
        String itemRef,
        String currencyKey,
        Instant openedAt,
        long expectedRevision) implements AuctionCommand {
    public OpenAuction {
        auctionId = Objects.requireNonNull(auctionId, "auctionId");
        sellerSubjectId = Objects.requireNonNull(sellerSubjectId, "sellerSubjectId");
        itemRef = requireNonBlank(itemRef, "itemRef");
        currencyKey = requireNonBlank(currencyKey, "currencyKey").toLowerCase(Locale.ROOT);
        openedAt = Objects.requireNonNull(openedAt, "openedAt");
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
