package sh.harold.fulcrum.standard.realm;

import sh.harold.fulcrum.core.artifact.ArtifactBlobLayout;
import sh.harold.fulcrum.core.artifact.ArtifactObjectAddress;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class RealmSnapshotLineageValidator {
    private static final Comparator<RealmSnapshotMetadata> SNAPSHOT_ORDER = Comparator
            .comparing((RealmSnapshotMetadata metadata) -> metadata.realmId().value())
            .thenComparing(metadata -> metadata.corpusId().value())
            .thenComparingLong(metadata -> metadata.revision().value())
            .thenComparing(RealmSnapshotMetadata::capturedAt)
            .thenComparing(metadata -> metadata.snapshotId().value());

    public RealmSnapshotLineageReport validate(
            String objectBucket,
            Set<String> supportedStateCompatibilityVersions,
            List<RealmSnapshotMetadata> snapshots) {
        String checkedBucket = RealmNames.requireNonBlank(objectBucket, "objectBucket");
        Set<String> supportedVersions = Set.copyOf(Objects.requireNonNull(
                supportedStateCompatibilityVersions,
                "supportedStateCompatibilityVersions"));
        List<RealmSnapshotMetadata> orderedSnapshots = List.copyOf(Objects.requireNonNull(snapshots, "snapshots"))
                .stream()
                .sorted(SNAPSHOT_ORDER)
                .toList();
        Map<RealmSnapshotId, RealmSnapshotMetadata> snapshotsById = new HashMap<>();
        Set<RealmSnapshotId> duplicateIds = new HashSet<>();
        for (RealmSnapshotMetadata snapshot : orderedSnapshots) {
            RealmSnapshotMetadata previous = snapshotsById.putIfAbsent(snapshot.snapshotId(), snapshot);
            if (previous != null) {
                duplicateIds.add(snapshot.snapshotId());
            }
        }

        List<RealmSnapshotLineageReceipt> receipts = new ArrayList<>();
        List<RealmSnapshotMetadata> acceptedSnapshots = new ArrayList<>();
        for (RealmSnapshotMetadata snapshot : orderedSnapshots) {
            List<RealmSnapshotLineageRejectionReason> reasons = rejectionReasons(
                    checkedBucket,
                    supportedVersions,
                    snapshotsById,
                    duplicateIds,
                    snapshot);
            if (reasons.isEmpty()) {
                receipts.add(new RealmSnapshotLineageReceipt(
                        snapshot.snapshotId(),
                        RealmSnapshotLineageStatus.ACCEPTED,
                        List.of()));
                acceptedSnapshots.add(snapshot);
            } else {
                receipts.add(new RealmSnapshotLineageReceipt(
                        snapshot.snapshotId(),
                        RealmSnapshotLineageStatus.REJECTED,
                        reasons));
            }
        }

        RealmSnapshotLineageStatus status = receipts.stream().anyMatch(receipt -> receipt.status() == RealmSnapshotLineageStatus.REJECTED)
                ? RealmSnapshotLineageStatus.REJECTED
                : RealmSnapshotLineageStatus.ACCEPTED;
        return new RealmSnapshotLineageReport(status, receipts, acceptedSnapshots);
    }

    private static List<RealmSnapshotLineageRejectionReason> rejectionReasons(
            String objectBucket,
            Set<String> supportedStateCompatibilityVersions,
            Map<RealmSnapshotId, RealmSnapshotMetadata> snapshotsById,
            Set<RealmSnapshotId> duplicateIds,
            RealmSnapshotMetadata snapshot) {
        List<RealmSnapshotLineageRejectionReason> reasons = new ArrayList<>();
        try {
            ArtifactObjectAddress expectedAddress = ArtifactBlobLayout.objectAddress(objectBucket, snapshot.artifactPin());
            if (!expectedAddress.equals(snapshot.objectAddress())) {
                reasons.add(RealmSnapshotLineageRejectionReason.OBJECT_ADDRESS_MISMATCH);
            }
        } catch (IllegalArgumentException exception) {
            reasons.add(RealmSnapshotLineageRejectionReason.MALFORMED_DIGEST_REFERENCE);
        }
        if (!supportedStateCompatibilityVersions.contains(snapshot.stateCompatibilityVersion())) {
            reasons.add(RealmSnapshotLineageRejectionReason.UNSUPPORTED_STATE_COMPATIBILITY);
        }
        if (duplicateIds.contains(snapshot.snapshotId())) {
            reasons.add(RealmSnapshotLineageRejectionReason.DUPLICATE_SNAPSHOT_ID);
        }
        parent(snapshot).ifPresent(parentSnapshotId -> addParentReasons(
                reasons,
                snapshotsById.get(parentSnapshotId),
                snapshot));
        return reasons;
    }

    private static void addParentReasons(
            List<RealmSnapshotLineageRejectionReason> reasons,
            RealmSnapshotMetadata parent,
            RealmSnapshotMetadata snapshot) {
        if (parent == null) {
            reasons.add(RealmSnapshotLineageRejectionReason.PARENT_SNAPSHOT_MISSING);
            return;
        }
        if (!parent.realmId().equals(snapshot.realmId()) || !parent.corpusId().equals(snapshot.corpusId())) {
            reasons.add(RealmSnapshotLineageRejectionReason.PARENT_SCOPE_MISMATCH);
        }
        if (!parent.capturedAt().isBefore(snapshot.capturedAt())) {
            reasons.add(RealmSnapshotLineageRejectionReason.PARENT_CAPTURED_NOT_EARLIER);
        }
        if (parent.revision().value() >= snapshot.revision().value()) {
            reasons.add(RealmSnapshotLineageRejectionReason.PARENT_REVISION_NOT_EARLIER);
        }
    }

    private static Optional<RealmSnapshotId> parent(RealmSnapshotMetadata snapshot) {
        return snapshot.parentSnapshotId();
    }
}
