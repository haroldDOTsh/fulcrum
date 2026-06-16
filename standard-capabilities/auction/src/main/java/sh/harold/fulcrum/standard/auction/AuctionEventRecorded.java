package sh.harold.fulcrum.standard.auction;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.List;
import java.util.Objects;

public record AuctionEventRecorded(
        AuctionSnapshot snapshot,
        List<AuctionEscrowEntry> escrowEntries,
        AuctionAuditEntry auditEntry,
        Revision revision) {
    public AuctionEventRecorded {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        escrowEntries = List.copyOf(Objects.requireNonNull(escrowEntries, "escrowEntries"));
        auditEntry = Objects.requireNonNull(auditEntry, "auditEntry");
        revision = Objects.requireNonNull(revision, "revision");
    }

    public String wireValue() {
        return auditEntry.wireValue();
    }
}
