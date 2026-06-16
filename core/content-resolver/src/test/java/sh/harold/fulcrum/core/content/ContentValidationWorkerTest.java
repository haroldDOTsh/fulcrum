package sh.harold.fulcrum.core.content;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.core.artifact.ArtifactBlobLayout;
import sh.harold.fulcrum.core.artifact.ArtifactObjectAddress;
import sh.harold.fulcrum.core.manifest.ArtifactPin;
import sh.harold.fulcrum.core.manifest.ContractPin;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ContentValidationWorkerTest {
    private static final String BUCKET = "artifact-store";
    private static final String CATALOG_REVISION = "catalog-2026-06-16.2";
    private static final String MODE_ID = "competitive";
    private static final String HOST_RUNTIME_ABI = "paper-26.1.2";
    private static final ExperienceId EXPERIENCE_ID = new ExperienceId("experience.duels");
    private static final PoolId POOL_ID = new PoolId("pool.paper.standard");
    private static final ContractPin CONTENT_CONTRACT = new ContractPin(new ContractName("fulcrum.content"), "1.0.0");
    private static final ContractPin MATCH_CONTRACT = new ContractPin(new ContractName("fulcrum.match"), "1.0.0");

    private final ContentValidationWorker worker = new ContentValidationWorker();

    @Test
    void validatesMapConfigAndContentPackCatalogEntries() {
        ContentCatalogEntry map = entry(
                "artifact.map.alpha",
                ContentArtifactKind.MAP_TEMPLATE,
                digest('a'),
                Set.of(EXPERIENCE_ID),
                Set.of(MODE_ID),
                Set.of(POOL_ID),
                List.of(CONTENT_CONTRACT, MATCH_CONTRACT),
                Optional.empty(),
                100,
                10,
                8192,
                true);
        ContentCatalogEntry config = entry(
                "artifact.config.competitive",
                ContentArtifactKind.CONFIG_MODE,
                digest('b'),
                Set.of(EXPERIENCE_ID),
                Set.of(MODE_ID),
                Set.of(),
                List.of(CONTENT_CONTRACT),
                Optional.empty(),
                100,
                20,
                2048,
                true);
        ContentCatalogEntry pack = entry(
                "artifact.pack.common",
                ContentArtifactKind.CONTENT_PACK,
                digest('c'),
                Set.of(),
                Set.of(),
                Set.of(),
                List.of(CONTENT_CONTRACT),
                Optional.empty(),
                50,
                30,
                4096,
                true);

        ContentValidationReport report = worker.validate(new ContentCatalog(
                CATALOG_REVISION,
                BUCKET,
                List.of(map, config, pack)));

        assertEquals(ContentValidationStatus.ACCEPTED, report.status());
        assertEquals(3, report.acceptedCandidates().size());
        assertEquals(
                List.of(ContentArtifactKind.MAP_TEMPLATE, ContentArtifactKind.CONFIG_MODE, ContentArtifactKind.CONTENT_PACK),
                report.acceptedCandidates().stream().map(ContentArtifactCandidate::kind).toList());
        assertTrue(report.receipts().stream().allMatch(receipt -> receipt.status() == ContentValidationStatus.ACCEPTED));
        assertEquals(CATALOG_REVISION, report.acceptedCandidates().get(0).catalogRevision());
        assertEquals(ContentArtifactReadiness.VALIDATED, report.acceptedCandidates().get(0).readiness());
    }

    @Test
    void rejectsInvalidCatalogEntriesWithReceipts() {
        ContentCatalogEntry mismatchedAddress = entry(
                "artifact.map.bad-address",
                ContentArtifactKind.MAP_TEMPLATE,
                digest('d'),
                new ArtifactObjectAddress("object://artifact-store/artifacts/sha-256/00/00/" + digest('e') + ".blob"),
                Set.of(EXPERIENCE_ID),
                Set.of(MODE_ID),
                Set.of(POOL_ID),
                List.of(CONTENT_CONTRACT),
                Optional.empty(),
                100,
                10,
                8192,
                true);
        ContentCatalogEntry unscopedMap = entry(
                "artifact.map.unscoped",
                ContentArtifactKind.MAP_TEMPLATE,
                digest('e'),
                Set.of(),
                Set.of(),
                Set.of(POOL_ID),
                List.of(CONTENT_CONTRACT),
                Optional.empty(),
                100,
                20,
                8192,
                true);
        ContentCatalogEntry unscopedConfig = entry(
                "artifact.config.unscoped",
                ContentArtifactKind.CONFIG_MODE,
                digest('f'),
                Set.of(EXPERIENCE_ID),
                Set.of(),
                Set.of(),
                List.of(CONTENT_CONTRACT),
                Optional.empty(),
                100,
                30,
                2048,
                true);
        ContentCatalogEntry emptyPack = entry(
                "artifact.pack.empty",
                ContentArtifactKind.CONTENT_PACK,
                digest('1'),
                Set.of(),
                Set.of(),
                Set.of(),
                List.of(),
                Optional.empty(),
                100,
                40,
                0,
                true);

        ContentValidationReport report = worker.validate(new ContentCatalog(
                CATALOG_REVISION,
                BUCKET,
                List.of(mismatchedAddress, unscopedMap, unscopedConfig, emptyPack)));

        assertEquals(ContentValidationStatus.REJECTED, report.status());
        assertTrue(report.acceptedCandidates().isEmpty());
        assertTrue(hasReason(report, mismatchedAddress, ContentValidationRejectionReason.OBJECT_ADDRESS_MISMATCH));
        assertTrue(hasReason(report, unscopedMap, ContentValidationRejectionReason.MAP_TEMPLATE_SCOPE_MISSING));
        assertTrue(hasReason(report, unscopedConfig, ContentValidationRejectionReason.CONFIG_MODE_SCOPE_MISSING));
        assertTrue(hasReason(report, emptyPack, ContentValidationRejectionReason.CONTRACT_PINS_MISSING));
        assertTrue(hasReason(report, emptyPack, ContentValidationRejectionReason.NON_POSITIVE_BYTE_LENGTH));
    }

    @Test
    void rejectsDuplicatePinAndSelectionOrder() {
        ContentCatalogEntry first = entry(
                "artifact.map.alpha",
                ContentArtifactKind.MAP_TEMPLATE,
                digest('a'),
                Set.of(EXPERIENCE_ID),
                Set.of(MODE_ID),
                Set.of(POOL_ID),
                List.of(CONTENT_CONTRACT),
                Optional.empty(),
                100,
                10,
                8192,
                true);
        ContentCatalogEntry duplicatePin = entry(
                "artifact.map.alpha",
                ContentArtifactKind.MAP_TEMPLATE,
                digest('a'),
                Set.of(EXPERIENCE_ID),
                Set.of(MODE_ID),
                Set.of(POOL_ID),
                List.of(CONTENT_CONTRACT),
                Optional.empty(),
                100,
                20,
                8192,
                true);
        ContentCatalogEntry duplicateOrder = entry(
                "artifact.map.beta",
                ContentArtifactKind.MAP_TEMPLATE,
                digest('b'),
                Set.of(EXPERIENCE_ID),
                Set.of(MODE_ID),
                Set.of(POOL_ID),
                List.of(CONTENT_CONTRACT),
                Optional.empty(),
                100,
                10,
                8192,
                true);

        ContentValidationReport report = worker.validate(new ContentCatalog(
                CATALOG_REVISION,
                BUCKET,
                List.of(first, duplicatePin, duplicateOrder)));

        assertEquals(ContentValidationStatus.REJECTED, report.status());
        assertEquals(1, report.acceptedCandidates().size());
        assertTrue(hasReason(report, duplicatePin, ContentValidationRejectionReason.DUPLICATE_ARTIFACT_PIN));
        assertTrue(hasReason(report, duplicateOrder, ContentValidationRejectionReason.DUPLICATE_SELECTION_ORDER));
    }

    @Test
    void validatedCandidatesFeedResolverWithoutFallbacks() {
        ContentCatalogEntry map = entry(
                "artifact.map.alpha",
                ContentArtifactKind.MAP_TEMPLATE,
                digest('a'),
                Set.of(EXPERIENCE_ID),
                Set.of(MODE_ID),
                Set.of(POOL_ID),
                List.of(CONTENT_CONTRACT, MATCH_CONTRACT),
                Optional.empty(),
                100,
                10,
                8192,
                true);
        ContentCatalogEntry config = entry(
                "artifact.config.competitive",
                ContentArtifactKind.CONFIG_MODE,
                digest('b'),
                Set.of(EXPERIENCE_ID),
                Set.of(MODE_ID),
                Set.of(),
                List.of(CONTENT_CONTRACT, MATCH_CONTRACT),
                Optional.empty(),
                100,
                20,
                2048,
                true);
        ContentValidationReport report = worker.validate(new ContentCatalog(
                CATALOG_REVISION,
                BUCKET,
                List.of(map, config)));

        ContentResolution resolution = new ContentResolver().resolve(
                new ContentResolutionRequest(
                        new ResolvedManifestId("resolved-manifest.duels.competitive.2"),
                        new ArtifactId("artifact.code.duels"),
                        EXPERIENCE_ID,
                        Optional.of(MODE_ID),
                        POOL_ID,
                        List.of(CONTENT_CONTRACT, MATCH_CONTRACT),
                        HOST_RUNTIME_ABI,
                        Optional.empty(),
                        "resolver-v1"),
                new ContentRotationPolicy(
                        "policy.duels.rotation",
                        "policy-2026-06-16.2",
                        CATALOG_REVISION,
                        List.of(ContentArtifactKind.MAP_TEMPLATE, ContentArtifactKind.CONFIG_MODE)),
                report.acceptedCandidates());

        assertEquals(ContentResolutionStatus.RESOLVED, resolution.status());
        assertFalse(resolution.resolvedManifest().orElseThrow().contentArtifacts().isEmpty());
    }

    private static ContentCatalogEntry entry(
            String artifactId,
            ContentArtifactKind kind,
            String digest,
            Set<ExperienceId> experienceIds,
            Set<String> modeIds,
            Set<PoolId> poolIds,
            List<ContractPin> contractPins,
            Optional<String> stateCompatibilityVersion,
            int rotationWeight,
            int rotationOrder,
            long byteLength,
            boolean enabled) {
        ArtifactPin pin = new ArtifactPin(new ArtifactId(artifactId), digest, kind.name().toLowerCase());
        return entry(
                artifactId,
                kind,
                digest,
                ArtifactBlobLayout.objectAddress(BUCKET, pin),
                experienceIds,
                modeIds,
                poolIds,
                contractPins,
                stateCompatibilityVersion,
                rotationWeight,
                rotationOrder,
                byteLength,
                enabled);
    }

    private static ContentCatalogEntry entry(
            String artifactId,
            ContentArtifactKind kind,
            String digest,
            ArtifactObjectAddress objectAddress,
            Set<ExperienceId> experienceIds,
            Set<String> modeIds,
            Set<PoolId> poolIds,
            List<ContractPin> contractPins,
            Optional<String> stateCompatibilityVersion,
            int rotationWeight,
            int rotationOrder,
            long byteLength,
            boolean enabled) {
        return new ContentCatalogEntry(
                new ArtifactPin(new ArtifactId(artifactId), digest, kind.name().toLowerCase()),
                kind,
                objectAddress,
                experienceIds,
                modeIds,
                poolIds,
                contractPins,
                HOST_RUNTIME_ABI,
                stateCompatibilityVersion,
                enabled,
                rotationWeight,
                rotationOrder,
                byteLength);
    }

    private static boolean hasReason(
            ContentValidationReport report,
            ContentCatalogEntry entry,
            ContentValidationRejectionReason reason) {
        return report.receipts().stream()
                .filter(receipt -> receipt.artifactPin().equals(entry.artifactPin()))
                .flatMap(receipt -> receipt.rejectionReasons().stream())
                .anyMatch(reason::equals);
    }

    private static String digest(char value) {
        return String.valueOf(value).repeat(64);
    }
}
