package sh.harold.fulcrum.standard.realm;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.core.artifact.ArtifactObjectAddress;
import sh.harold.fulcrum.core.manifest.ArtifactPin;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record RealmSnapshotMetadata(
        RealmId realmId,
        RealmSnapshotId snapshotId,
        RealmCorpusId corpusId,
        Optional<RealmSnapshotId> parentSnapshotId,
        ArtifactPin artifactPin,
        ArtifactObjectAddress objectAddress,
        String stateCompatibilityVersion,
        long byteLength,
        Instant capturedAt,
        Revision revision) {
    public RealmSnapshotMetadata {
        realmId = Objects.requireNonNull(realmId, "realmId");
        snapshotId = Objects.requireNonNull(snapshotId, "snapshotId");
        corpusId = Objects.requireNonNull(corpusId, "corpusId");
        parentSnapshotId = parentSnapshotId == null ? Optional.empty() : parentSnapshotId;
        artifactPin = Objects.requireNonNull(artifactPin, "artifactPin");
        objectAddress = Objects.requireNonNull(objectAddress, "objectAddress");
        stateCompatibilityVersion = RealmNames.requireNonBlank(stateCompatibilityVersion, "stateCompatibilityVersion");
        if (byteLength <= 0) {
            throw new IllegalArgumentException("byteLength must be positive");
        }
        capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        revision = Objects.requireNonNull(revision, "revision");
    }
}
