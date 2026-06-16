package sh.harold.fulcrum.standard.auction;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record AuctionProjection(
        Map<AuctionId, AuctionProjectionRow> auctions,
        Map<AuctionEscrowAccountId, Long> escrowedMinorUnits,
        List<AuctionAuditEntry> auditEntries) {
    public AuctionProjection {
        auctions = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(auctions, "auctions")));
        escrowedMinorUnits = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(escrowedMinorUnits, "escrowedMinorUnits")));
        auditEntries = List.copyOf(Objects.requireNonNull(auditEntries, "auditEntries"));
    }

    public static AuctionProjection empty() {
        return new AuctionProjection(Map.of(), Map.of(), List.of());
    }

    public static AuctionProjection rebuild(List<AuctionEventRecorded> events) {
        Objects.requireNonNull(events, "events");
        LinkedHashMap<AuctionId, AuctionProjectionRow> auctions = new LinkedHashMap<>();
        LinkedHashMap<AuctionEscrowAccountId, Long> escrows = new LinkedHashMap<>();
        ArrayList<AuctionAuditEntry> audit = new ArrayList<>();
        for (AuctionEventRecorded event : events) {
            AuctionProjectionRow current = auctions.get(event.snapshot().auctionId());
            if (current != null && event.revision().value() <= current.revision().value()) {
                throw new IllegalArgumentException("auction projection replay requires increasing revisions per auction");
            }
            auctions.put(event.snapshot().auctionId(), new AuctionProjectionRow(event.snapshot(), event.revision()));
            for (AuctionEscrowEntry escrowEntry : event.escrowEntries()) {
                AuctionEscrowAccountId accountId = escrowEntry.accountId();
                long nextAmount = Math.addExact(escrows.getOrDefault(accountId, 0L), escrowEntry.signedAmount());
                if (nextAmount < 0) {
                    throw new IllegalArgumentException("auction escrow compensation cannot over-release held funds");
                }
                escrows.put(accountId, nextAmount);
            }
            audit.add(event.auditEntry());
        }
        return new AuctionProjection(auctions, escrows, audit);
    }

    public Optional<AuctionProjectionRow> auction(AuctionId auctionId) {
        return Optional.ofNullable(auctions.get(Objects.requireNonNull(auctionId, "auctionId")));
    }

    public long escrowedAmount(AuctionId auctionId, SubjectId subjectId, String currencyKey) {
        return escrowedMinorUnits.getOrDefault(new AuctionEscrowAccountId(auctionId, subjectId, currencyKey), 0L);
    }
}
