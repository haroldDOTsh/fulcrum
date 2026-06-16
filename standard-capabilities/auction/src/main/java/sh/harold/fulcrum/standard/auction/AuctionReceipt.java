package sh.harold.fulcrum.standard.auction;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record AuctionReceipt(
        boolean accepted,
        Optional<AuctionSnapshot> snapshot,
        List<AuctionEscrowEntry> escrowEntries,
        Optional<AuctionAuditEntry> auditEntry,
        Optional<Revision> revision,
        long fencingEpoch,
        String idempotencyKey,
        String commandId,
        Optional<String> rejectionReason) {
    public AuctionReceipt {
        snapshot = snapshot == null ? Optional.empty() : snapshot;
        escrowEntries = List.copyOf(escrowEntries == null ? List.of() : escrowEntries);
        auditEntry = auditEntry == null ? Optional.empty() : auditEntry;
        revision = revision == null ? Optional.empty() : revision;
        idempotencyKey = idempotencyKey == null ? "" : idempotencyKey.trim();
        commandId = commandId == null ? "" : commandId.trim();
        rejectionReason = rejectionReason == null ? Optional.empty() : rejectionReason;
        if (accepted && (snapshot.isEmpty() || auditEntry.isEmpty() || revision.isEmpty())) {
            throw new IllegalArgumentException("accepted auction receipt requires snapshot, audit entry, and revision");
        }
        if (!accepted && rejectionReason.isEmpty()) {
            throw new IllegalArgumentException("rejected auction receipt requires reason");
        }
    }

    public static AuctionReceipt accepted(
            AuctionSnapshot snapshot,
            List<AuctionEscrowEntry> escrowEntries,
            AuctionAuditEntry auditEntry,
            Revision revision,
            long fencingEpoch,
            String idempotencyKey,
            String commandId) {
        return new AuctionReceipt(
                true,
                Optional.of(Objects.requireNonNull(snapshot, "snapshot")),
                List.copyOf(Objects.requireNonNull(escrowEntries, "escrowEntries")),
                Optional.of(Objects.requireNonNull(auditEntry, "auditEntry")),
                Optional.of(Objects.requireNonNull(revision, "revision")),
                fencingEpoch,
                requireNonBlank(idempotencyKey, "idempotencyKey"),
                requireNonBlank(commandId, "commandId"),
                Optional.empty());
    }

    public static AuctionReceipt rejected(String reason) {
        return new AuctionReceipt(false, Optional.empty(), List.of(), Optional.empty(), Optional.empty(), -1, "", "", Optional.of(requireNonBlank(reason, "reason")));
    }

    public String wireValue() {
        return accepted
                ? "accepted|%s|%d|%d|%s|%s".formatted(
                        snapshot.orElseThrow().auctionId().value(),
                        snapshot.orElseThrow().highestBidMinorUnits(),
                        revision.orElseThrow().value(),
                        idempotencyKey,
                        commandId)
                : "rejected|" + rejectionReason.orElseThrow();
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
