package sh.harold.fulcrum.standard.auction;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class AuctionProjectionTest {
    private static final Instant NOW = Instant.parse("2026-06-17T01:15:00Z");
    private static final AuctionId AUCTION_ID = new AuctionId("auction-projection-1");
    private static final SubjectId SELLER = subject("00000000-0000-0000-0000-000000001601");
    private static final SubjectId BIDDER = subject("00000000-0000-0000-0000-000000001602");
    private static final PrincipalId PRINCIPAL = new PrincipalId("standard-auction-projection");
    private static final String CURRENCY = "coins";

    @Test
    void rebuildsListingEscrowAndAuditProjections() {
        AuctionProjection projection = AuctionProjection.rebuild(List.of(opened(), bidHeld()));

        assertEquals(100, projection.auction(AUCTION_ID).orElseThrow().snapshot().highestBidMinorUnits());
        assertEquals(100, projection.escrowedAmount(AUCTION_ID, BIDDER, CURRENCY));
        assertEquals(List.of("OPENED", "BID_ACCEPTED"),
                projection.auditEntries().stream().map(AuctionAuditEntry::action).toList());
    }

    @Test
    void rejectsCompensationThatOverReleasesEscrow() {
        AuctionEscrowEntry release = escrow("release-without-hold", AuctionEscrowAction.RELEASE, 100, new Revision(2));
        AuctionAuditEntry audit = audit("audit-release", "BID_ACCEPTED", 100, new Revision(2));
        AuctionEventRecorded releaseEvent = new AuctionEventRecorded(snapshot(BIDDER, 100, new Revision(2)), List.of(release), audit, new Revision(2));

        assertThrows(IllegalArgumentException.class, () -> AuctionProjection.rebuild(List.of(opened(), releaseEvent)));
    }

    private static AuctionEventRecorded opened() {
        Revision revision = new Revision(1);
        return new AuctionEventRecorded(
                snapshot(null, 0, revision),
                List.of(),
                audit("audit-open", "OPENED", 0, revision),
                revision);
    }

    private static AuctionEventRecorded bidHeld() {
        Revision revision = new Revision(2);
        return new AuctionEventRecorded(
                snapshot(BIDDER, 100, revision),
                List.of(escrow("hold", AuctionEscrowAction.HOLD, 100, revision)),
                audit("audit-bid", "BID_ACCEPTED", 100, revision),
                revision);
    }

    private static AuctionSnapshot snapshot(SubjectId bidder, long amount, Revision revision) {
        return new AuctionSnapshot(
                AUCTION_ID,
                SELLER,
                "item:projection",
                CURRENCY,
                Optional.ofNullable(bidder),
                amount,
                AuctionStatus.OPEN,
                PRINCIPAL,
                NOW.plusSeconds(revision.value()));
    }

    private static AuctionEscrowEntry escrow(
            String entryId,
            AuctionEscrowAction action,
            long amount,
            Revision revision) {
        return new AuctionEscrowEntry(
                entryId,
                AUCTION_ID,
                BIDDER,
                action,
                amount,
                CURRENCY,
                PRINCIPAL,
                NOW.plusSeconds(revision.value()),
                "idem-" + entryId,
                "command-" + entryId,
                revision);
    }

    private static AuctionAuditEntry audit(String entryId, String action, long amount, Revision revision) {
        return new AuctionAuditEntry(
                entryId,
                AUCTION_ID,
                action,
                Optional.of(BIDDER),
                amount,
                CURRENCY,
                PRINCIPAL,
                NOW.plusSeconds(revision.value()),
                revision);
    }

    private static SubjectId subject(String uuid) {
        return new SubjectId(UUID.fromString(uuid));
    }
}
