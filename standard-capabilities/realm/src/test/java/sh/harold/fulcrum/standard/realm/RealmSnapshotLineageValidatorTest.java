package sh.harold.fulcrum.standard.realm;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.core.artifact.ArtifactBlobLayout;
import sh.harold.fulcrum.core.artifact.ArtifactObjectAddress;
import sh.harold.fulcrum.core.manifest.ArtifactPin;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RealmSnapshotLineageValidatorTest {
    private static final String BUCKET = "artifact-store";
    private static final String STATE_VERSION = "realm-state-v1";
    private static final RealmId REALM_ID = new RealmId("realm.alpha");
    private static final RealmCorpusId CORPUS_ID = new RealmCorpusId("realm-corpus.alpha");
    private static final Instant BASE_TIME = Instant.parse("2026-06-16T20:00:00Z");

    private final RealmSnapshotLineageValidator validator = new RealmSnapshotLineageValidator();

    @Test
    void acceptsCorpusScopedLineageForSupportedStateVersion() {
        RealmSnapshotMetadata root = metadata(
                "snapshot.root",
                CORPUS_ID,
                Optional.empty(),
                digest('a'),
                STATE_VERSION,
                BASE_TIME,
                1);
        RealmSnapshotMetadata child = metadata(
                "snapshot.child",
                CORPUS_ID,
                Optional.of(root.snapshotId()),
                digest('b'),
                STATE_VERSION,
                BASE_TIME.plusSeconds(60),
                2);

        RealmSnapshotLineageReport report = validator.validate(BUCKET, Set.of(STATE_VERSION), List.of(child, root));

        assertEquals(RealmSnapshotLineageStatus.ACCEPTED, report.status());
        assertEquals(List.of(root, child), report.acceptedSnapshots());
        assertTrue(report.receipts().stream().allMatch(receipt -> receipt.status() == RealmSnapshotLineageStatus.ACCEPTED));
    }

    @Test
    void rejectsUnsupportedStateAndWrongObjectAddress() {
        RealmSnapshotMetadata snapshot = metadata(
                "snapshot.bad",
                CORPUS_ID,
                Optional.empty(),
                digest('c'),
                "realm-state-v2",
                new ArtifactObjectAddress("object://artifact-store/artifacts/sha-256/00/00/" + digest('d') + ".blob"),
                BASE_TIME,
                1);

        RealmSnapshotLineageReport report = validator.validate(BUCKET, Set.of(STATE_VERSION), List.of(snapshot));

        assertEquals(RealmSnapshotLineageStatus.REJECTED, report.status());
        assertTrue(hasReason(report, snapshot.snapshotId(), RealmSnapshotLineageRejectionReason.UNSUPPORTED_STATE_COMPATIBILITY));
        assertTrue(hasReason(report, snapshot.snapshotId(), RealmSnapshotLineageRejectionReason.OBJECT_ADDRESS_MISMATCH));
    }

    @Test
    void rejectsParentOutsideCorpusAndNonIncreasingLineage() {
        RealmSnapshotMetadata parent = metadata(
                "snapshot.parent",
                new RealmCorpusId("realm-corpus.other"),
                Optional.empty(),
                digest('e'),
                STATE_VERSION,
                BASE_TIME.plusSeconds(60),
                3);
        RealmSnapshotMetadata child = metadata(
                "snapshot.child",
                CORPUS_ID,
                Optional.of(parent.snapshotId()),
                digest('f'),
                STATE_VERSION,
                BASE_TIME,
                2);

        RealmSnapshotLineageReport report = validator.validate(BUCKET, Set.of(STATE_VERSION), List.of(parent, child));

        assertEquals(RealmSnapshotLineageStatus.REJECTED, report.status());
        assertTrue(hasReason(report, child.snapshotId(), RealmSnapshotLineageRejectionReason.PARENT_SCOPE_MISMATCH));
        assertTrue(hasReason(report, child.snapshotId(), RealmSnapshotLineageRejectionReason.PARENT_CAPTURED_NOT_EARLIER));
        assertTrue(hasReason(report, child.snapshotId(), RealmSnapshotLineageRejectionReason.PARENT_REVISION_NOT_EARLIER));
    }

    @Test
    void rejectsMissingParentAndDuplicateSnapshotId() {
        RealmSnapshotMetadata duplicateA = metadata(
                "snapshot.duplicate",
                CORPUS_ID,
                Optional.empty(),
                digest('a'),
                STATE_VERSION,
                BASE_TIME,
                1);
        RealmSnapshotMetadata duplicateB = metadata(
                "snapshot.duplicate",
                CORPUS_ID,
                Optional.empty(),
                digest('b'),
                STATE_VERSION,
                BASE_TIME.plusSeconds(60),
                2);
        RealmSnapshotMetadata missingParent = metadata(
                "snapshot.orphan",
                CORPUS_ID,
                Optional.of(new RealmSnapshotId("snapshot.missing")),
                digest('c'),
                STATE_VERSION,
                BASE_TIME.plusSeconds(120),
                3);

        RealmSnapshotLineageReport report = validator.validate(
                BUCKET,
                Set.of(STATE_VERSION),
                List.of(duplicateA, duplicateB, missingParent));

        assertEquals(RealmSnapshotLineageStatus.REJECTED, report.status());
        assertTrue(hasReason(report, duplicateA.snapshotId(), RealmSnapshotLineageRejectionReason.DUPLICATE_SNAPSHOT_ID));
        assertTrue(hasReason(report, duplicateB.snapshotId(), RealmSnapshotLineageRejectionReason.DUPLICATE_SNAPSHOT_ID));
        assertTrue(hasReason(report, missingParent.snapshotId(), RealmSnapshotLineageRejectionReason.PARENT_SNAPSHOT_MISSING));
    }

    private static RealmSnapshotMetadata metadata(
            String snapshotId,
            RealmCorpusId corpusId,
            Optional<RealmSnapshotId> parentSnapshotId,
            String digest,
            String stateCompatibilityVersion,
            Instant capturedAt,
            long revision) {
        ArtifactPin pin = pin(snapshotId, digest);
        return metadata(
                snapshotId,
                corpusId,
                parentSnapshotId,
                digest,
                stateCompatibilityVersion,
                ArtifactBlobLayout.objectAddress(BUCKET, pin),
                capturedAt,
                revision);
    }

    private static RealmSnapshotMetadata metadata(
            String snapshotId,
            RealmCorpusId corpusId,
            Optional<RealmSnapshotId> parentSnapshotId,
            String digest,
            String stateCompatibilityVersion,
            ArtifactObjectAddress objectAddress,
            Instant capturedAt,
            long revision) {
        return new RealmSnapshotMetadata(
                REALM_ID,
                new RealmSnapshotId(snapshotId),
                corpusId,
                parentSnapshotId,
                pin(snapshotId, digest),
                objectAddress,
                stateCompatibilityVersion,
                4096,
                capturedAt,
                new Revision(revision));
    }

    private static ArtifactPin pin(String snapshotId, String digest) {
        return new ArtifactPin(new ArtifactId("artifact.realm." + snapshotId), digest, "realm-snapshot-v1");
    }

    private static boolean hasReason(
            RealmSnapshotLineageReport report,
            RealmSnapshotId snapshotId,
            RealmSnapshotLineageRejectionReason reason) {
        return report.receipts().stream()
                .filter(receipt -> receipt.snapshotId().equals(snapshotId))
                .flatMap(receipt -> receipt.rejectionReasons().stream())
                .anyMatch(reason::equals);
    }

    private static String digest(char value) {
        return String.valueOf(value).repeat(64);
    }
}
