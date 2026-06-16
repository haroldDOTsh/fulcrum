package sh.harold.fulcrum.core.content;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.core.manifest.ArtifactPin;
import sh.harold.fulcrum.core.manifest.ContractPin;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ContentResolverTest {
    private static final ExperienceId EXPERIENCE_ID = new ExperienceId("experience.duels");
    private static final ExperienceId OTHER_EXPERIENCE_ID = new ExperienceId("experience.build");
    private static final PoolId POOL_ID = new PoolId("pool.paper.standard");
    private static final PoolId OTHER_POOL_ID = new PoolId("pool.paper.large");
    private static final String MODE_ID = "competitive";
    private static final String HOST_RUNTIME_ABI = "paper-26.1.2";
    private static final ContractPin MATCH_CONTRACT = new ContractPin(new ContractName("fulcrum.match"), "1.0.0");
    private static final ContractPin CONTENT_CONTRACT = new ContractPin(new ContractName("fulcrum.content"), "1.0.0");

    private final ContentResolver resolver = new ContentResolver();

    @Test
    void filtersIncompatibleCandidatesAndBuildsResolvedManifestTrace() {
        ContentArtifactCandidate selectedMap = candidate(
                "artifact.map.alpha",
                ContentArtifactKind.MAP_TEMPLATE,
                Set.of(EXPERIENCE_ID),
                Set.of(MODE_ID),
                Set.of(POOL_ID),
                List.of(CONTENT_CONTRACT, MATCH_CONTRACT),
                HOST_RUNTIME_ABI,
                Optional.empty(),
                ContentArtifactReadiness.VALIDATED,
                true,
                100,
                20,
                'a');
        ContentArtifactCandidate selectedConfig = candidate(
                "artifact.config.competitive",
                ContentArtifactKind.CONFIG_MODE,
                Set.of(EXPERIENCE_ID),
                Set.of(MODE_ID),
                Set.of(POOL_ID),
                List.of(CONTENT_CONTRACT, MATCH_CONTRACT),
                HOST_RUNTIME_ABI,
                Optional.empty(),
                ContentArtifactReadiness.VALIDATED,
                true,
                100,
                10,
                'b');
        ContentArtifactCandidate wrongHost = candidate(
                "artifact.map.old-host",
                ContentArtifactKind.MAP_TEMPLATE,
                Set.of(EXPERIENCE_ID),
                Set.of(MODE_ID),
                Set.of(POOL_ID),
                List.of(CONTENT_CONTRACT, MATCH_CONTRACT),
                "paper-25.0.0",
                Optional.empty(),
                ContentArtifactReadiness.VALIDATED,
                true,
                100,
                1,
                'c');
        ContentArtifactCandidate wrongContract = candidate(
                "artifact.map.old-contract",
                ContentArtifactKind.MAP_TEMPLATE,
                Set.of(EXPERIENCE_ID),
                Set.of(MODE_ID),
                Set.of(POOL_ID),
                List.of(CONTENT_CONTRACT),
                HOST_RUNTIME_ABI,
                Optional.empty(),
                ContentArtifactReadiness.VALIDATED,
                true,
                100,
                2,
                'd');
        ContentArtifactCandidate wrongExperience = candidate(
                "artifact.map.other-experience",
                ContentArtifactKind.MAP_TEMPLATE,
                Set.of(OTHER_EXPERIENCE_ID),
                Set.of(MODE_ID),
                Set.of(POOL_ID),
                List.of(CONTENT_CONTRACT, MATCH_CONTRACT),
                HOST_RUNTIME_ABI,
                Optional.empty(),
                ContentArtifactReadiness.VALIDATED,
                true,
                100,
                3,
                'e');
        ContentArtifactCandidate disabledConfig = candidate(
                "artifact.config.disabled",
                ContentArtifactKind.CONFIG_MODE,
                Set.of(EXPERIENCE_ID),
                Set.of(MODE_ID),
                Set.of(POOL_ID),
                List.of(CONTENT_CONTRACT, MATCH_CONTRACT),
                HOST_RUNTIME_ABI,
                Optional.empty(),
                ContentArtifactReadiness.VALIDATED,
                false,
                100,
                1,
                'f');

        ContentResolution resolution = resolver.resolve(
                request(Optional.empty()),
                policy(ContentArtifactKind.MAP_TEMPLATE, ContentArtifactKind.CONFIG_MODE),
                List.of(disabledConfig, wrongExperience, wrongContract, selectedConfig, wrongHost, selectedMap));

        assertEquals(ContentResolutionStatus.RESOLVED, resolution.status());
        assertEquals(
                List.of(selectedMap.artifactPin(), selectedConfig.artifactPin()),
                resolution.resolvedManifest().orElseThrow().contentArtifacts());
        assertEquals(List.of(CONTENT_CONTRACT, MATCH_CONTRACT), resolution.resolvedManifest().orElseThrow().contractPins());
        assertEquals("catalog-2026-06-16.1", resolution.trace().catalogRevision());
        assertTrue(hasReason(resolution, wrongHost.artifactPin(), ContentRejectionReason.HOST_RUNTIME_ABI_MISMATCH));
        assertTrue(hasReason(resolution, wrongContract.artifactPin(), ContentRejectionReason.CONTRACT_MISMATCH));
        assertTrue(hasReason(resolution, wrongExperience.artifactPin(), ContentRejectionReason.EXPERIENCE_MISMATCH));
        assertTrue(hasReason(resolution, disabledConfig.artifactPin(), ContentRejectionReason.DISABLED_BY_POLICY));
        assertTrue(resolution.trace().missingKinds().isEmpty());
    }

    @Test
    void resolutionIsDeterministicAcrossCatalogInputOrder() {
        ContentArtifactCandidate mapA = candidate(
                "artifact.map.a",
                ContentArtifactKind.MAP_TEMPLATE,
                Set.of(EXPERIENCE_ID),
                Set.of(MODE_ID),
                Set.of(POOL_ID),
                List.of(MATCH_CONTRACT, CONTENT_CONTRACT),
                HOST_RUNTIME_ABI,
                Optional.empty(),
                ContentArtifactReadiness.VALIDATED,
                true,
                50,
                10,
                'a');
        ContentArtifactCandidate mapB = candidate(
                "artifact.map.b",
                ContentArtifactKind.MAP_TEMPLATE,
                Set.of(EXPERIENCE_ID),
                Set.of(MODE_ID),
                Set.of(POOL_ID),
                List.of(CONTENT_CONTRACT, MATCH_CONTRACT),
                HOST_RUNTIME_ABI,
                Optional.empty(),
                ContentArtifactReadiness.VALIDATED,
                true,
                50,
                10,
                'b');

        ContentResolution first = resolver.resolve(
                request(Optional.empty()),
                policy(ContentArtifactKind.MAP_TEMPLATE),
                List.of(mapB, mapA));
        ContentResolution second = resolver.resolve(
                request(Optional.empty()),
                policy(ContentArtifactKind.MAP_TEMPLATE),
                List.of(mapA, mapB));

        assertEquals(ContentResolutionStatus.RESOLVED, first.status());
        assertEquals(first.resolvedManifest(), second.resolvedManifest());
        assertEquals(List.of(mapA.artifactPin()), first.trace().selectedPins());
        assertEquals(first.trace().selectedPins(), second.trace().selectedPins());
    }

    @Test
    void rejectsEmptyEligibleSetWithAuditableReasons() {
        ContentArtifactCandidate wrongMode = candidate(
                "artifact.map.casual",
                ContentArtifactKind.MAP_TEMPLATE,
                Set.of(EXPERIENCE_ID),
                Set.of("casual"),
                Set.of(POOL_ID),
                List.of(CONTENT_CONTRACT, MATCH_CONTRACT),
                HOST_RUNTIME_ABI,
                Optional.empty(),
                ContentArtifactReadiness.VALIDATED,
                true,
                100,
                10,
                'c');
        ContentArtifactCandidate pendingValidation = candidate(
                "artifact.map.pending",
                ContentArtifactKind.MAP_TEMPLATE,
                Set.of(EXPERIENCE_ID),
                Set.of(MODE_ID),
                Set.of(POOL_ID),
                List.of(CONTENT_CONTRACT, MATCH_CONTRACT),
                HOST_RUNTIME_ABI,
                Optional.empty(),
                ContentArtifactReadiness.PENDING_VALIDATION,
                true,
                100,
                20,
                'd');
        ContentArtifactCandidate wrongPool = candidate(
                "artifact.map.large",
                ContentArtifactKind.MAP_TEMPLATE,
                Set.of(EXPERIENCE_ID),
                Set.of(MODE_ID),
                Set.of(OTHER_POOL_ID),
                List.of(CONTENT_CONTRACT, MATCH_CONTRACT),
                HOST_RUNTIME_ABI,
                Optional.empty(),
                ContentArtifactReadiness.VALIDATED,
                true,
                100,
                30,
                'e');

        ContentResolution resolution = resolver.resolve(
                request(Optional.empty()),
                policy(ContentArtifactKind.MAP_TEMPLATE),
                List.of(wrongMode, pendingValidation, wrongPool));

        assertEquals(ContentResolutionStatus.REJECTED, resolution.status());
        assertFalse(resolution.resolvedManifest().isPresent());
        assertEquals(List.of(ContentArtifactKind.MAP_TEMPLATE), resolution.trace().missingKinds());
        assertTrue(resolution.trace().selectedPins().isEmpty());
        assertTrue(hasReason(resolution, wrongMode.artifactPin(), ContentRejectionReason.MODE_MISMATCH));
        assertTrue(hasReason(resolution, pendingValidation.artifactPin(), ContentRejectionReason.NOT_VALIDATED));
        assertTrue(hasReason(resolution, wrongPool.artifactPin(), ContentRejectionReason.POOL_MISMATCH));
    }

    @Test
    void rejectsUnsupportedStateCompatibilityVersion() {
        ContentArtifactCandidate oldSnapshot = candidate(
                "artifact.snapshot.old",
                ContentArtifactKind.STATE_SNAPSHOT,
                Set.of(EXPERIENCE_ID),
                Set.of(MODE_ID),
                Set.of(POOL_ID),
                List.of(CONTENT_CONTRACT, MATCH_CONTRACT),
                HOST_RUNTIME_ABI,
                Optional.of("state-v1"),
                ContentArtifactReadiness.VALIDATED,
                true,
                100,
                10,
                'a');

        ContentResolution resolution = resolver.resolve(
                request(Optional.of("state-v2")),
                policy(ContentArtifactKind.STATE_SNAPSHOT),
                List.of(oldSnapshot));

        assertEquals(ContentResolutionStatus.REJECTED, resolution.status());
        assertEquals(List.of(ContentArtifactKind.STATE_SNAPSHOT), resolution.trace().missingKinds());
        assertTrue(hasReason(resolution, oldSnapshot.artifactPin(), ContentRejectionReason.STATE_COMPATIBILITY_MISMATCH));
    }

    @Test
    void rejectsMalformedDigestReferencesFromCatalogCandidates() {
        ArtifactPin pin = new ArtifactPin(new ArtifactId("artifact.map.bad"), "not-a-digest", "map-template-v1");

        assertThrows(IllegalArgumentException.class, () -> candidate(
                pin,
                ContentArtifactKind.MAP_TEMPLATE,
                Set.of(EXPERIENCE_ID),
                Set.of(MODE_ID),
                Set.of(POOL_ID),
                List.of(CONTENT_CONTRACT, MATCH_CONTRACT),
                HOST_RUNTIME_ABI,
                Optional.empty(),
                ContentArtifactReadiness.VALIDATED,
                true,
                100,
                10));
    }

    private static ContentResolutionRequest request(Optional<String> stateCompatibilityVersion) {
        return new ContentResolutionRequest(
                new ResolvedManifestId("resolved-manifest.duels.competitive.1"),
                new ArtifactId("artifact.code.duels"),
                EXPERIENCE_ID,
                Optional.of(MODE_ID),
                POOL_ID,
                List.of(MATCH_CONTRACT, CONTENT_CONTRACT),
                HOST_RUNTIME_ABI,
                stateCompatibilityVersion,
                "resolver-v1");
    }

    private static ContentRotationPolicy policy(ContentArtifactKind... requiredKinds) {
        return new ContentRotationPolicy(
                "policy.duels.rotation",
                "policy-2026-06-16.1",
                "catalog-2026-06-16.1",
                List.of(requiredKinds));
    }

    private static ContentArtifactCandidate candidate(
            String artifactId,
            ContentArtifactKind kind,
            Set<ExperienceId> experienceIds,
            Set<String> modeIds,
            Set<PoolId> poolIds,
            List<ContractPin> contractPins,
            String hostRuntimeAbi,
            Optional<String> stateCompatibilityVersion,
            ContentArtifactReadiness readiness,
            boolean enabled,
            int rotationWeight,
            int rotationOrder,
            char digestSeed) {
        return candidate(
                new ArtifactPin(new ArtifactId(artifactId), String.valueOf(digestSeed).repeat(64), kind.name().toLowerCase()),
                kind,
                experienceIds,
                modeIds,
                poolIds,
                contractPins,
                hostRuntimeAbi,
                stateCompatibilityVersion,
                readiness,
                enabled,
                rotationWeight,
                rotationOrder);
    }

    private static ContentArtifactCandidate candidate(
            ArtifactPin artifactPin,
            ContentArtifactKind kind,
            Set<ExperienceId> experienceIds,
            Set<String> modeIds,
            Set<PoolId> poolIds,
            List<ContractPin> contractPins,
            String hostRuntimeAbi,
            Optional<String> stateCompatibilityVersion,
            ContentArtifactReadiness readiness,
            boolean enabled,
            int rotationWeight,
            int rotationOrder) {
        return new ContentArtifactCandidate(
                artifactPin,
                kind,
                "catalog-2026-06-16.1",
                experienceIds,
                modeIds,
                poolIds,
                contractPins,
                hostRuntimeAbi,
                stateCompatibilityVersion,
                readiness,
                enabled,
                rotationWeight,
                rotationOrder);
    }

    private static boolean hasReason(
            ContentResolution resolution,
            ArtifactPin artifactPin,
            ContentRejectionReason reason) {
        return resolution.trace().candidateEvaluations().stream()
                .filter(evaluation -> evaluation.artifactPin().equals(artifactPin))
                .flatMap(evaluation -> evaluation.rejectionReasons().stream())
                .anyMatch(reason::equals);
    }
}
