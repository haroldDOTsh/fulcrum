package sh.harold.fulcrum.standard.auction;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;

public record AuctionProjectionRow(AuctionSnapshot snapshot, Revision revision) {
    public AuctionProjectionRow {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        revision = Objects.requireNonNull(revision, "revision");
    }

    public AuctionId auctionId() {
        return snapshot.auctionId();
    }
}
