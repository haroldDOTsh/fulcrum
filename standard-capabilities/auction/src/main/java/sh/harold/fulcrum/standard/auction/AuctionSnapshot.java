package sh.harold.fulcrum.standard.auction;

import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public record AuctionSnapshot(
        AuctionId auctionId,
        SubjectId sellerSubjectId,
        String itemRef,
        String currencyKey,
        Optional<SubjectId> highestBidderSubjectId,
        long highestBidMinorUnits,
        AuctionStatus status,
        PrincipalId updatedBy,
        Instant updatedAt) {
    public AuctionSnapshot {
        auctionId = Objects.requireNonNull(auctionId, "auctionId");
        sellerSubjectId = Objects.requireNonNull(sellerSubjectId, "sellerSubjectId");
        itemRef = requireNonBlank(itemRef, "itemRef");
        currencyKey = requireNonBlank(currencyKey, "currencyKey").toLowerCase(Locale.ROOT);
        highestBidderSubjectId = highestBidderSubjectId == null ? Optional.empty() : highestBidderSubjectId;
        status = Objects.requireNonNull(status, "status");
        updatedBy = Objects.requireNonNull(updatedBy, "updatedBy");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (highestBidMinorUnits < 0) {
            throw new IllegalArgumentException("highestBidMinorUnits must not be negative");
        }
        if (highestBidMinorUnits == 0 && highestBidderSubjectId.isPresent()) {
            throw new IllegalArgumentException("zero highest bid cannot have bidder");
        }
        if (highestBidMinorUnits > 0 && highestBidderSubjectId.isEmpty()) {
            throw new IllegalArgumentException("positive highest bid requires bidder");
        }
    }

    public static AuctionSnapshot open(OpenAuction command, PrincipalId openedBy) {
        return new AuctionSnapshot(
                command.auctionId(),
                command.sellerSubjectId(),
                command.itemRef(),
                command.currencyKey(),
                Optional.empty(),
                0,
                AuctionStatus.OPEN,
                openedBy,
                command.openedAt());
    }

    public AuctionSnapshot withBid(PlaceAuctionBid command, PrincipalId updatedBy) {
        if (sellerSubjectId.equals(command.bidderSubjectId())) {
            throw new IllegalArgumentException("seller cannot bid on own auction");
        }
        if (!currencyKey.equals(command.currencyKey())) {
            throw new IllegalArgumentException("auction bid currency must match listing currency");
        }
        if (command.bidMinorUnits() <= highestBidMinorUnits) {
            throw new IllegalArgumentException("auction bid must exceed current highest bid");
        }
        return new AuctionSnapshot(
                auctionId,
                sellerSubjectId,
                itemRef,
                currencyKey,
                Optional.of(command.bidderSubjectId()),
                command.bidMinorUnits(),
                status,
                updatedBy,
                command.placedAt());
    }

    public String wireValue(Revision revision) {
        Objects.requireNonNull(revision, "revision");
        return "auctionId=%s\nsellerSubjectId=%s\nitemRef=%s\ncurrencyKey=%s\nhighestBidderSubjectId=%s\nhighestBidMinorUnits=%d\nstatus=%s\nupdatedBy=%s\nupdatedAt=%s\nrevision=%d"
                .formatted(
                        auctionId.value(),
                        sellerSubjectId.value(),
                        itemRef,
                        currencyKey,
                        highestBidderSubjectId.map(subject -> subject.value().toString()).orElse(""),
                        highestBidMinorUnits,
                        status.name(),
                        updatedBy.value(),
                        updatedAt,
                        revision.value());
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
