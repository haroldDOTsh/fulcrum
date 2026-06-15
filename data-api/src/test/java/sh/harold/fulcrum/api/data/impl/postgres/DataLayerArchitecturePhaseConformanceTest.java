package sh.harold.fulcrum.api.data.impl.postgres;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DataLayerArchitecturePhaseConformanceTest {
    @Test
    void everyArchitecturePhaseExitHasExecutableProof() {
        String architecture = readRepoFile("refactor/data-layer-architecture.md");
        Set<String> expectedPhaseIds = new LinkedHashSet<>(List.of(
            "Phase 0",
            "Phase 1",
            "Phase 2",
            "Phase 3",
            "Phase 4",
            "Phase 5",
            "Phase 6"
        ));
        List<PhaseProof> proofs = phaseProofs();

        assertThat(proofs).extracting(PhaseProof::phaseId)
            .containsExactlyElementsOf(expectedPhaseIds);

        for (PhaseProof proof : proofs) {
            assertThat(architecture)
                .as(proof.phaseId() + " phase header")
                .contains(proof.phaseId());
            assertThat(architecture)
                .as(proof.phaseId() + " exit criterion")
                .contains(proof.exitNeedle());
            assertThat(proof.methods())
                .as(proof.phaseId() + " executable phase proofs")
                .isNotEmpty();
            proof.methods().forEach(method -> assertDefaultRunnableProofMethod(proof.phaseId(), method));
        }
    }

    private static List<PhaseProof> phaseProofs() {
        return List.of(
            new PhaseProof(
                "Phase 0",
                "hello-world command round-trips log",
                List.of(
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/DataAuthorityCommandContractManifestTest.java",
                        "contractCommandsRoundTripThroughAuthorityLogTransport"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/AuthorityLogCommandPortTest.java",
                        "acceptedCommandsAppendCommandEventStateAndResponseFrames"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/WatermarkedDataAuthorityCacheTest.java",
                        "quotedRankReadReturnsSatisfiedCachedSnapshot"
                    )
                )
            ),
            new PhaseProof(
                "Phase 1",
                "no runtime holds a Postgres credential for player data",
                List.of(
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/postgres/CanonicalSchemaDdlBoundaryTest.java",
                        "gameNodeProductionCodeDoesNotReferenceDirectPostgresAccess"
                    ),
                    proof(
                        "runtime/src/test/java/sh/harold/fulcrum/fundamentals/data/DataAuthorityFeatureTest.java",
                        "bundledGameNodeConfigDoesNotShipPostgresCredentials"
                    ),
                    proof(
                        "runtime-velocity/src/test/java/sh/harold/fulcrum/velocity/fundamentals/data/VelocityDataAuthorityFeatureTest.java",
                        "bundledGameNodeConfigDoesNotShipPostgresCredentials"
                    )
                )
            ),
            new PhaseProof(
                "Phase 2",
                "permission checks read from cache",
                List.of(
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/DataLayerStorePlacementArchitectureTest.java",
                        "commandContractsMatchDocumentedStorePlacements"
                    ),
                    proof(
                        "runtime/src/test/java/sh/harold/fulcrum/runtime/threading/ThreadBoundaryStaticTest.java",
                        "paperRankFeatureUsesQuotedAuthorityReads"
                    ),
                    proof(
                        "runtime-velocity/src/test/java/sh/harold/fulcrum/velocity/api/rank/VelocityRankStaticTest.java",
                        "velocityRankUtilsUsesQuotedAuthorityReads"
                    )
                )
            ),
            new PhaseProof(
                "Phase 3",
                "minigame match load flows through the log",
                List.of(
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/DataAuthorityCommandContractManifestTest.java",
                        "routePartitionKeyVectorsCoverEveryCommandType"
                    ),
                    proof(
                        "runtime/src/test/java/sh/harold/fulcrum/runtime/threading/ThreadBoundaryStaticTest.java",
                        "minigameMatchesRequireAuthorityCommandPort"
                    )
                )
            ),
            new PhaseProof(
                "Phase 4",
                "audit/analytics partitioned and bounded",
                List.of(
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/AuthorityLifecyclePolicyMigrationTest.java",
                        "lifecycleMigrationRegistersEveryAppendHeavyTable"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/AuthorityLifecyclePolicyMigrationTest.java",
                        "lifecyclePoliciesPointAtRealTimestampColumnsAndBrinIndexes"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/AuthorityLifecyclePartitionPlannerTest.java",
                        "plannerBuildsCurrentAndNextMonthlyPartitionWorkOrdersFromMigrationPolicies"
                    )
                )
            ),
            new PhaseProof(
                "Phase 5",
                "no lost or doubled writes and no stale-writer clobber",
                List.of(
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/DataAuthorityCommandContractManifestTest.java",
                        "contractRejectsAnyRevisionForCompareRequiredCommands"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/AuthorityFencingCommandPortTest.java",
                        "rejectsWhenClaimDoesNotMatchCommandRoute"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/CachedAuthorityCommandPortTest.java",
                        "sameIdempotencyKeyWithDifferentFingerprintDelegatesToDurableAuthority"
                    )
                )
            ),
            new PhaseProof(
                "Phase 6",
                "documented RPO/RTO met in a restore drill",
                List.of(
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/events/InMemoryAuthorityHotStateProjectionTest.java",
                        "compactedRankStateRecordRebuildsHotStateProjection"
                    ),
                    proof(
                        "registry-service/src/test/java/sh/harold/fulcrum/registry/RegistryServiceRestoreReadbackTest.java",
                        "restoreRegistrySnapshotsReportsReadableBackendAndProxyReservations"
                    ),
                    proof(
                        "registry-service/src/test/java/sh/harold/fulcrum/registry/console/commands/AuthorityStateRestoreCommandTest.java",
                        "verifyRunsRestoreDrillAndPrintsEvidence"
                    )
                )
            )
        );
    }

    private static ProofMethod proof(String sourcePath, String methodName) {
        return new ProofMethod(sourcePath, methodName);
    }

    private static void assertDefaultRunnableProofMethod(String phaseId, ProofMethod proof) {
        Path sourcePath = repoPath(proof.sourcePath());
        assertThat(sourcePath)
            .as(phaseId + " proof source " + proof.sourcePath())
            .exists();
        String source = readPath(sourcePath);
        int methodIndex = source.indexOf("void " + proof.methodName() + "(");
        assertThat(methodIndex)
            .as(phaseId + " proof method " + proof.sourcePath() + "#" + proof.methodName())
            .isGreaterThanOrEqualTo(0);
        String classPreamble = source.substring(0, Math.max(0, source.indexOf("class ")));
        assertThat(classPreamble)
            .as(phaseId + " proof class is default-runnable")
            .doesNotContain("@Disabled")
            .doesNotContain("@Tag(\"live-postgres\")")
            .doesNotContain("@Testcontainers");
        String methodPreamble = source.substring(Math.max(0, methodIndex - 240), methodIndex);
        assertThat(methodPreamble)
            .as(phaseId + " proof method is a JUnit test")
            .contains("@Test")
            .doesNotContain("@Disabled");
    }

    private static String readRepoFile(String relativePath) {
        return readPath(repoPath(relativePath));
    }

    private static Path repoPath(String relativePath) {
        Path workingDirectory = Path.of("").toAbsolutePath();
        for (Path base : List.of(workingDirectory, workingDirectory.getParent())) {
            if (base == null) {
                continue;
            }
            Path candidate = base.resolve(relativePath);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Repo file not found from " + workingDirectory + ": " + relativePath);
    }

    private static String readPath(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read repo file: " + path, exception);
        }
    }

    private record PhaseProof(
        String phaseId,
        String exitNeedle,
        List<ProofMethod> methods
    ) {
    }

    private record ProofMethod(
        String sourcePath,
        String methodName
    ) {
    }
}
