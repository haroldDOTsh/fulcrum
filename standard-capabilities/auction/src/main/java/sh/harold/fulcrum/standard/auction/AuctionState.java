package sh.harold.fulcrum.standard.auction;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record AuctionState(
        Optional<AuctionSnapshot> current,
        List<AuctionEscrowEntry> escrowEntries,
        List<AuctionAuditEntry> auditEntries) {
    public AuctionState {
        current = current == null ? Optional.empty() : current;
        escrowEntries = List.copyOf(Objects.requireNonNull(escrowEntries, "escrowEntries"));
        auditEntries = List.copyOf(Objects.requireNonNull(auditEntries, "auditEntries"));
    }

    public static AuctionState empty() {
        return new AuctionState(Optional.empty(), List.of(), List.of());
    }

    public AuctionState append(
            AuctionSnapshot snapshot,
            List<AuctionEscrowEntry> nextEscrowEntries,
            AuctionAuditEntry auditEntry) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(auditEntry, "auditEntry");
        ArrayList<AuctionEscrowEntry> escrows = new ArrayList<>(escrowEntries);
        escrows.addAll(List.copyOf(Objects.requireNonNull(nextEscrowEntries, "nextEscrowEntries")));
        ArrayList<AuctionAuditEntry> audit = new ArrayList<>(auditEntries);
        audit.add(auditEntry);
        return new AuctionState(Optional.of(snapshot), List.copyOf(escrows), List.copyOf(audit));
    }

    public String wireValue(Revision revision) {
        Objects.requireNonNull(revision, "revision");
        return current.map(snapshot -> snapshot.wireValue(revision)
                        + "\nescrowEntryCount=" + escrowEntries.size()
                        + "\nauditEntryCount=" + auditEntries.size())
                .orElse("empty=true\nrevision=" + revision.value() + "\nescrowEntryCount=0\nauditEntryCount=0");
    }
}
