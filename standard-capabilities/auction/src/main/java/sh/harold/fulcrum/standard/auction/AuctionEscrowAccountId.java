package sh.harold.fulcrum.standard.auction;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.util.Locale;
import java.util.Objects;

public record AuctionEscrowAccountId(AuctionId auctionId, SubjectId subjectId, String currencyKey) {
    public AuctionEscrowAccountId {
        auctionId = Objects.requireNonNull(auctionId, "auctionId");
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        currencyKey = requireNonBlank(currencyKey, "currencyKey").toLowerCase(Locale.ROOT);
    }

    public String value() {
        return auctionId.value() + ":" + subjectId.value() + ":" + currencyKey;
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
