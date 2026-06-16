package sh.harold.fulcrum.standard.realm;

import java.util.List;
import java.util.Objects;

public record RealmSnapshotLineageReport(
        RealmSnapshotLineageStatus status,
        List<RealmSnapshotLineageReceipt> receipts,
        List<RealmSnapshotMetadata> acceptedSnapshots) {
    public RealmSnapshotLineageReport {
        status = Objects.requireNonNull(status, "status");
        receipts = List.copyOf(Objects.requireNonNull(receipts, "receipts"));
        acceptedSnapshots = List.copyOf(Objects.requireNonNull(acceptedSnapshots, "acceptedSnapshots"));
        if (status == RealmSnapshotLineageStatus.ACCEPTED
                && receipts.stream().anyMatch(receipt -> receipt.status() == RealmSnapshotLineageStatus.REJECTED)) {
            throw new IllegalArgumentException("accepted lineage reports must not contain rejected receipts");
        }
    }
}
