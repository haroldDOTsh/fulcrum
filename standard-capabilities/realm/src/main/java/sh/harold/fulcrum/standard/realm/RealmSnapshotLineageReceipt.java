package sh.harold.fulcrum.standard.realm;

import java.util.List;
import java.util.Objects;

public record RealmSnapshotLineageReceipt(
        RealmSnapshotId snapshotId,
        RealmSnapshotLineageStatus status,
        List<RealmSnapshotLineageRejectionReason> rejectionReasons) {
    public RealmSnapshotLineageReceipt {
        snapshotId = Objects.requireNonNull(snapshotId, "snapshotId");
        status = Objects.requireNonNull(status, "status");
        rejectionReasons = List.copyOf(Objects.requireNonNull(rejectionReasons, "rejectionReasons"));
        if (status == RealmSnapshotLineageStatus.ACCEPTED && !rejectionReasons.isEmpty()) {
            throw new IllegalArgumentException("accepted lineage receipts must not carry rejection reasons");
        }
        if (status == RealmSnapshotLineageStatus.REJECTED && rejectionReasons.isEmpty()) {
            throw new IllegalArgumentException("rejected lineage receipts must carry rejection reasons");
        }
    }
}
