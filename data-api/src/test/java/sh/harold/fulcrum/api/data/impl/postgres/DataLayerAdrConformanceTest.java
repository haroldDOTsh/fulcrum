package sh.harold.fulcrum.api.data.impl.postgres;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class DataLayerAdrConformanceTest {
    private static final String SCHEMA_MIGRATION = FulcrumDataMigrations.SCHEMA_MIGRATION;

    @Test
    void adrCatalogKeepsTheAcceptedDataLayerDecisionSet() {
        String adrs = readRepoFile("refactor/data-layer-adrs.md");

        assertThat(adrs)
            .contains(
                "ADR-0001",
                "ADR-0002",
                "ADR-0003",
                "ADR-0004",
                "ADR-0005",
                "ADR-0006",
                "ADR-0007",
                "ADR-0008",
                "ADR-0009",
                "ADR-0010",
                "ADR-0011"
            );
    }

    @Test
    void substrateDecisionsAreOptimalFirstRatherThanOssGated() {
        String docs = readRepoFile("refactor/data-layer-architecture.md")
            + "\n"
            + readRepoFile("refactor/data-layer-adrs.md");

        assertThat(docs)
            .contains(
                "Operational fit comes first",
                "OSS/permissive licensing is a preference and tie-breaker, not a gate"
            )
            .doesNotContain(
                "Self-hosted, OSS-licensed engines",
                "OSS constraint applies",
                "self-hosted OSS stack",
                "under the OSS constraint",
                "Consider only if the constraint softens",
                "The OSS constraint favors"
            );
    }

    @Test
    void documentedDataLayerHooksHaveSchemaContractMigrationAndLifecycleEvidence() {
        String docs = readRepoFile("refactor/data-layer-architecture.md")
            + "\n"
            + readRepoFile("refactor/data-layer-adrs.md");
        String lifecycleSql = readResource(SCHEMA_MIGRATION);
        FulcrumSchemaContract contract = FulcrumSchemaContract.loadDefault();

        for (AdrEvidenceHook hook : adrEvidenceHooks()) {
            assertThat(docs)
                .as(hook.tableName() + " documented invariant")
                .contains(hook.documentedNeedle());
            FulcrumSchemaContract.TableContract table =
                contract.requireDataApiOwnedTable(hook.tableName(), hook.readerService(), hook.writerService());
            assertThat(table.dataOwner())
                .as(hook.tableName() + " data owner")
                .isEqualTo(hook.dataOwner());
            assertThat(FulcrumDataMigrations.all())
                .as(hook.tableName() + " creation migration is canonical")
                .contains(table.createdBy());
            assertThat(readResource(table.createdBy()))
                .as(hook.tableName() + " creation migration creates table")
                .containsPattern(createTablePattern(hook.tableName()));
            if (hook.lifecyclePolicyRequired()) {
                assertThat(lifecycleSql)
                    .as(hook.tableName() + " lifecycle policy")
                    .contains("'" + hook.tableName() + "'");
            }
        }
    }

    @Test
    void everyDataLayerInvariantHasExecutableProof() {
        String docs = readRepoFile("refactor/data-layer-architecture.md")
            + "\n"
            + readRepoFile("refactor/data-layer-adrs.md");
        Set<String> expectedIds = new LinkedHashSet<>(List.of(
            "P1",
            "P2",
            "P3",
            "P4",
            "P5",
            "P6",
            "P7",
            "P8",
            "ADR-0001",
            "ADR-0002",
            "ADR-0003",
            "ADR-0004",
            "ADR-0005",
            "ADR-0006",
            "ADR-0007",
            "ADR-0008",
            "ADR-0009",
            "ADR-0010",
            "ADR-0011"
        ));
        List<InvariantProof> proofs = invariantProofs();

        assertThat(proofs).extracting(InvariantProof::id)
            .containsExactlyInAnyOrderElementsOf(expectedIds);
        assertThat(new LinkedHashSet<>(proofs.stream().map(InvariantProof::id).toList()))
            .as("conformance proof ids must be unique")
            .hasSameSizeAs(proofs);

        for (InvariantProof proof : proofs) {
            assertThat(docs)
                .as(proof.id() + " documented source reference")
                .contains(proof.documentedNeedle());
            assertThat(proof.methods())
                .as(proof.id() + " executable proof methods")
                .isNotEmpty();
            boolean hasDefaultRunnableProof = false;
            List<ProofMethod> liveGatedProofs = new ArrayList<>();
            for (ProofMethod method : proof.methods()) {
                boolean liveGated = assertProofMethod(proof.id(), method);
                if (liveGated) {
                    liveGatedProofs.add(method);
                } else {
                    hasDefaultRunnableProof = true;
                }
            }
            assertThat(hasDefaultRunnableProof)
                .as(proof.id() + " default-runnable proof; live-gated proofs: " + liveGatedProofs)
                .isTrue();
        }
    }

    @Test
    void livePostgresProofsExposeExternalTargetFallback() {
        List<String> livePostgresProofs = List.of(
            "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/PostgresDataAuthorityIntegrationTest.java",
            "registry-service/src/test/java/sh/harold/fulcrum/registry/persistence/PostgresRegistryNodeSnapshotStoreIntegrationTest.java"
        );

        for (String proofSource : livePostgresProofs) {
            String source = readRepoFile(proofSource);
            assertThat(source)
                .as(proofSource + " must be explicitly live-gated")
                .contains(
                    "@Tag(\"live-postgres\")",
                    "fulcrum.test.postgres.jdbcUrl",
                    "fulcrum.test.postgres.username",
                    "fulcrum.test.postgres.password",
                    "fulcrum.test.postgres.allowMutation",
                    "fulcrum.test.postgres.requireLive",
                    "FULCRUM_TEST_POSTGRES_JDBC_URL",
                    "FULCRUM_TEST_POSTGRES_USERNAME",
                    "FULCRUM_TEST_POSTGRES_PASSWORD",
                    "FULCRUM_TEST_POSTGRES_ALLOW_MUTATION",
                    "FULCRUM_TEST_POSTGRES_REQUIRE_LIVE",
                    "requireLivePostgresTarget",
                    "externalPostgresTarget",
                    "unavailableLivePostgres",
                    "livePostgresRequired",
                    "POSTGRES.start()"
                )
                .doesNotContain(
                    "@Testcontainers",
                    "@Container",
                    "disabledWithoutDocker = true"
                );
        }

        assertThat(readRepoFile(
            "registry-service/src/test/java/sh/harold/fulcrum/registry/persistence/PostgresRegistryNodeSnapshotStoreIntegrationTest.java"
        ))
            .as("registry snapshot live proof mutates roles and database privileges")
            .contains(
                "fulcrum.test.postgres.allowRoleDdl",
                "FULCRUM_TEST_POSTGRES_ALLOW_ROLE_DDL"
            );
    }

    @Test
    void livePostgresProofsDocumentExternalTargetAndHardGate() {
        assertThat(readRepoFile("data-api/README.md"))
            .as("live PostgreSQL proof operator contract")
            .contains(
                "live-postgres",
                "FULCRUM_TEST_POSTGRES_JDBC_URL",
                "fulcrum.test.postgres.jdbcUrl",
                "FULCRUM_TEST_POSTGRES_ALLOW_MUTATION=true",
                "fulcrum.test.postgres.allowMutation=true",
                "FULCRUM_TEST_POSTGRES_ALLOW_ROLE_DDL=true",
                "fulcrum.test.postgres.allowRoleDdl=true",
                "FULCRUM_TEST_POSTGRES_REQUIRE_LIVE=true",
                "fulcrum.test.postgres.requireLive=true"
            );
    }

    @Test
    void livePostgresSystemPropertiesReachForkedTestWorkers() {
        assertThat(readRootRepoFile("build.gradle.kts"))
            .as("Gradle test tasks must forward live PostgreSQL -D properties to forked JUnit workers")
            .contains(
                "tasks.withType<Test>",
                "fulcrum.test.postgres.",
                "systemProperty"
            );
    }

    @Test
    void conformanceLedgerSeparatesDefaultStaticAndLiveEvidence() {
        String ledger = readRepoFile("refactor/data-layer-conformance.md");

        assertThat(readRepoFile("refactor/README.md"))
            .as("refactor index points reviewers at the evidence ledger")
            .contains("data-layer-conformance.md");
        assertThat(ledger)
            .as("ledger is evidence status, not a completion certificate")
            .contains(
                "Evidence ledger, not a completion certificate",
                "default-runnable",
                "static-contract",
                "live-postgres",
                "live-target-substrate",
                "remaining-deployment",
                "Passing `.\\gradlew.bat test` is not a claim that live PostgreSQL behavior was",
                "Passing `.\\gradlew.bat build` is not a claim that Docker/Testcontainers"
            );
        assertThat(ledger)
            .as("ledger records the fail-closed live PostgreSQL witness commands")
            .contains(
                "\"-Dfulcrum.test.postgres.requireLive=true\" :data-api:test",
                "\"-Dfulcrum.test.postgres.requireLive=true\" :registry-service:test",
                "PostgresDataAuthorityIntegrationTest",
                "PostgresRegistryNodeSnapshotStoreIntegrationTest",
                "FULCRUM_TEST_POSTGRES_ALLOW_ROLE_DDL=true"
            );
        assertThat(ledger)
            .as("ledger covers every invariant and ADR")
            .contains(
                "P1",
                "P2",
                "P3",
                "P4",
                "P5",
                "P6",
                "P7",
                "P8",
                "ADR-0001",
                "ADR-0002",
                "ADR-0003",
                "ADR-0004",
                "ADR-0005",
                "ADR-0006",
                "ADR-0007",
                "ADR-0008",
                "ADR-0009",
                "ADR-0010",
                "ADR-0011"
            );
        assertThat(ledger)
            .as("ledger covers every architecture phase exit")
            .contains(
                "Phase 0",
                "Phase 1",
                "Phase 2",
                "Phase 3",
                "Phase 4",
                "Phase 5",
                "Phase 6"
            );
    }

    @Test
    void bundledGameNodeNegativeCapabilityManifestsTrackReadContractFingerprint() {
        String readFingerprint = readRepoFile(
            "data-api/src/test/resources/contracts/data-authority-read-contract.sha256"
        ).trim();

        for (String manifestPath : List.of(
            "runtime/src/main/resources/META-INF/fulcrum/game-node-negative-capabilities.properties",
            "runtime-velocity/src/main/resources/META-INF/fulcrum/game-node-negative-capabilities.properties"
        )) {
            String manifest = readRepoFile(manifestPath);

            assertThat(manifest)
                .as(manifestPath + " forbids direct data authority and store access")
                .contains(
                    "forbid-local-authority=true",
                    "forbid-direct-store-config=true",
                    "store.direct.sql",
                    "store.direct.document",
                    "driver.jdbc.sql",
                    "pool.direct.sql",
                    "data-authority.read-schema-version=1"
                )
                .doesNotContain("store.direct.wide-column");
            assertThat(propertyValue(manifest, "data-authority.read-contract-fingerprint"))
                .as(manifestPath + " read contract fingerprint")
                .isEqualTo(readFingerprint);
        }
    }

    private static List<AdrEvidenceHook> adrEvidenceHooks() {
        return List.of(
            new AdrEvidenceHook(
                "authority_commands",
                "authority-command-audit",
                "registry-service",
                "registry-service",
                "authority_commands",
                true
            ),
            new AdrEvidenceHook(
                "authority_events",
                "authority-event-log",
                "projection-worker",
                "registry-service",
                "domain events",
                true
            ),
            new AdrEvidenceHook(
                "authority_state_changelog",
                "authority-state-log",
                "projection-worker",
                "registry-service",
                "compacted per-aggregate changelog",
                true
            ),
            new AdrEvidenceHook(
                "authority_state_restore_runs",
                "authority-state-restore",
                "ops",
                "registry-service",
                "restore drill",
                true
            ),
            new AdrEvidenceHook(
                "authority_partition_epochs",
                "authority-concurrency",
                "registry-service",
                "registry-service",
                "fencing epoch",
                false
            ),
            new AdrEvidenceHook(
                "authority_writer_claims",
                "authority-concurrency",
                "ops",
                "registry-service",
                "fencing-token pattern",
                false
            ),
            new AdrEvidenceHook(
                "authority_projection_replay_runs",
                "authority-projection-replay",
                "projection-worker",
                "projection-worker",
                "rebuild",
                true
            ),
            new AdrEvidenceHook(
                "registry_node_snapshots",
                "registry-control-plane",
                "registry-service",
                "registry-service",
                "node/slot snapshots",
                false
            )
        );
    }

    private static List<InvariantProof> invariantProofs() {
        return List.of(
            new InvariantProof(
                "P1",
                "One writer per aggregate",
                List.of(
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/AuthorityFencingCommandPortTest.java",
                        "stampsAuthorityEpochBeforeDelegating"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/PostgresDataAuthorityIntegrationTest.java",
                        "writerClaimReceiptRejectsSupersededOwnerBeforeNextWrite"
                    )
                )
            ),
            new InvariantProof(
                "P2",
                "The log is the system of record for change; stores are projections",
                List.of(
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/events/InMemoryAuthorityHotStateProjectionTest.java",
                        "eventLogRebuildsEquivalentHotStateProjection"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/PostgresDataAuthorityIntegrationTest.java",
                        "acceptedCommandWritesReceiptEventAndStateSnapshot"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/PostgresDataAuthorityIntegrationTest.java",
                        "stateRestoreDrillRebuildsMissingSnapshotFromChangelog"
                    )
                )
            ),
            new InvariantProof(
                "P3",
                "Game nodes are credential-light clients",
                List.of(
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/guard/GameNodeStorageGuardTest.java",
                        "rejectsPostgresSectionForGameNode"
                    ),
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
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/postgres/DataLayerAdrConformanceTest.java",
                        "bundledGameNodeNegativeCapabilityManifestsTrackReadContractFingerprint"
                    )
                )
            ),
            new InvariantProof(
                "P4",
                "Correctness is enforced, not declared",
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
            new InvariantProof(
                "P5",
                "Reads never hit the write primary",
                List.of(
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/DataLayerStorePlacementArchitectureTest.java",
                        "readContractsMatchDocumentedStorePlacements"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/WatermarkedDataAuthorityCacheTest.java",
                        "quotedRankReadReturnsSatisfiedCachedSnapshot"
                    )
                )
            ),
            new InvariantProof(
                "P6",
                "Connection count is decoupled from fleet size",
                List.of(
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/postgres/PostgresConnectionBudgetTest.java",
                        "reportRejectsPositiveGameNodePostgresPool"
                    ),
                    proof(
                        "registry-service/src/test/java/sh/harold/fulcrum/registry/RegistryServicePostgresConnectionBudgetTest.java",
                        "configuredCeilingFlagsOverBudgetDocket"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/postgres/PostgresConnectionAdapterTest.java",
                        "defaultsToPgBouncerTransactionPoolingSafeDriverSettings"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/postgres/PostgresConnectionAdapterTest.java",
                        "transactionPoolingRejectsServerPreparedStatementOverrides"
                    )
                )
            ),
            new InvariantProof(
                "P7",
                "Right store for the access pattern",
                List.of(
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/DataLayerStorePlacementArchitectureTest.java",
                        "executableStorePlacementsMatchArchitectureTable"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/DataLayerStorePlacementArchitectureTest.java",
                        "architectureStorePlacementRowsUseKnownStoreTaxonomy"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/DataLayerStorePlacementArchitectureTest.java",
                        "commandContractsMatchDocumentedStorePlacements"
                    ),
                    proof(
                        "registry-service/src/test/java/sh/harold/fulcrum/registry/authority/AuthoritySubstratePreflightTest.java",
                        "targetModeAcceptsWhenDeclaredAndActualSubstratesAreTarget"
                    ),
                    proof(
                        "registry-service/src/test/java/sh/harold/fulcrum/registry/authority/AuthoritySubstratePreflightTest.java",
                        "targetModeRejectsDeclaredTargetWhenActualRuntimeIsCompatibility"
                    )
                )
            ),
            new InvariantProof(
                "P8",
                "Authoring is contract-first and generated",
                List.of(
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/DataAuthorityCommandContractManifestTest.java",
                        "contractManifestCoversEveryCommandType"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/AuthorityContractArtifactsTest.java",
                        "generatedArtifactsCoverAdr0008CodegenOutputs"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/authority/client/AuthorityCommandsTest.java",
                        "rankCommandOwnsScopeIdempotencyAndExpectedRevision"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/postgres/CanonicalSchemaDdlBoundaryTest.java",
                        "productionDdlLivesOnlyInDataApiMigrations"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/postgres/CanonicalSchemaDdlBoundaryTest.java",
                        "gameNodeProductionCodeDoesNotHandAssembleAuthorityCommandEnvelopes"
                    )
                )
            ),
            new InvariantProof(
                "ADR-0001",
                "ADR-0001",
                List.of(
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/events/InMemoryAuthorityHotStateProjectionTest.java",
                        "eventLogRebuildsEquivalentHotStateProjection"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/PostgresDataAuthorityIntegrationTest.java",
                        "acceptedCommandWritesReceiptEventAndStateSnapshot"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/PostgresDataAuthorityIntegrationTest.java",
                        "stateRestoreDrillRebuildsMissingSnapshotFromChangelog"
                    )
                )
            ),
            new InvariantProof(
                "ADR-0002",
                "ADR-0002",
                List.of(
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/AuthorityDomainTopologyTest.java",
                        "declaresConsumerGroupAndPrincipalForEveryCommandDomain"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/DataAuthorityCommandContractManifestTest.java",
                        "routePartitionKeyVectorsCoverEveryCommandType"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/AuthorityFencingCommandPortTest.java",
                        "stampsAuthorityEpochBeforeDelegating"
                    )
                )
            ),
            new InvariantProof(
                "ADR-0003",
                "ADR-0003",
                List.of(
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/DataLayerStorePlacementArchitectureTest.java",
                        "executableStorePlacementsMatchArchitectureTable"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/DataLayerStorePlacementArchitectureTest.java",
                        "architectureStorePlacementRowsUseKnownStoreTaxonomy"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/DataLayerStorePlacementArchitectureTest.java",
                        "commandContractsMatchDocumentedStorePlacements"
                    ),
                    proof(
                        "registry-service/src/test/java/sh/harold/fulcrum/registry/authority/AuthoritySubstratePreflightTest.java",
                        "targetModeAcceptsWhenDeclaredAndActualSubstratesAreTarget"
                    ),
                    proof(
                        "registry-service/src/test/java/sh/harold/fulcrum/registry/authority/AuthoritySubstratePreflightTest.java",
                        "targetModeRejectsDeclaredTargetWhenActualRuntimeIsCompatibility"
                    )
                )
            ),
            new InvariantProof(
                "ADR-0004",
                "ADR-0004",
                List.of(
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/DataAuthorityCommandContractManifestTest.java",
                        "contractManifestClassifiesCompareRequiredCommands"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/DataAuthorityCommandContractManifestTest.java",
                        "contractRejectsAnyRevisionForCompareRequiredCommands"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/PostgresDataAuthorityIntegrationTest.java",
                        "reusedIdempotencyKeyWithDifferentPayloadIsRejectedAndRecorded"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/CachedAuthorityCommandPortTest.java",
                        "cacheReadFailureDelegatesToDurableAuthorityAndRefreshesCache"
                    )
                )
            ),
            new InvariantProof(
                "ADR-0005",
                "ADR-0005",
                List.of(
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/guard/GameNodeCapabilityManifestTest.java",
                        "rejectsDirectStoreConfigWhenForbidden"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/postgres/CanonicalSchemaDdlBoundaryTest.java",
                        "gameNodeProductionCodeDoesNotReferenceDirectPostgresAccess"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/guard/GameNodeStartupAttestationTest.java",
                        "rejectsDirectStoreConfigInEffectiveConfig"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/AuthorityPrincipalCommandPortTest.java",
                        "rejectsMessageBusCommandWithoutVerifiedPrincipal"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/AuthorityLogTopologyTest.java",
                        "topologyDeclaresDocumentedTopicFamilies"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/postgres/DataLayerAdrConformanceTest.java",
                        "bundledGameNodeNegativeCapabilityManifestsTrackReadContractFingerprint"
                    )
                )
            ),
            new InvariantProof(
                "ADR-0006",
                "ADR-0006",
                List.of(
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/postgres/PostgresConnectionBudgetTest.java",
                        "reportRejectsPositiveGameNodePostgresPool"
                    ),
                    proof(
                        "registry-service/src/test/java/sh/harold/fulcrum/registry/RegistryServicePostgresConnectionBudgetTest.java",
                        "configuredCeilingFlagsOverBudgetDocket"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/postgres/PostgresConnectionAdapterTest.java",
                        "defaultsToPgBouncerTransactionPoolingSafeDriverSettings"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/postgres/PostgresConnectionAdapterTest.java",
                        "transactionPoolingRejectsServerPreparedStatementOverrides"
                    )
                )
            ),
            new InvariantProof(
                "ADR-0007",
                "ADR-0007",
                List.of(
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/AuthorityLifecyclePolicyMigrationTest.java",
                        "lifecycleMigrationRegistersEveryAppendHeavyTable"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/PostgresDataAuthorityIntegrationTest.java",
                        "stateRestoreDrillRebuildsMissingSnapshotFromChangelog"
                    ),
                    proof(
                        "registry-service/src/test/java/sh/harold/fulcrum/registry/RegistryServiceRestoreReadbackTest.java",
                        "restoreRegistrySnapshotsReportsReadableBackendAndProxyReservations"
                    )
                )
            ),
            new InvariantProof(
                "ADR-0008",
                "ADR-0008",
                List.of(
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/postgres/FulcrumSchemaContractTest.java",
                        "schemaContractCoversEveryCanonicalMigrationTable"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/AuthorityContractArtifactsTest.java",
                        "generatedArtifactsCoverAdr0008CodegenOutputs"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/authority/client/AuthorityCommandsTest.java",
                        "rankCommandOwnsScopeIdempotencyAndExpectedRevision"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/postgres/CanonicalSchemaDdlBoundaryTest.java",
                        "productionDdlLivesOnlyInDataApiMigrations"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/postgres/CanonicalSchemaDdlBoundaryTest.java",
                        "gameNodeProductionCodeDoesNotHandAssembleAuthorityCommandEnvelopes"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/DataAuthorityCommandContractManifestTest.java",
                        "contractCommandsRoundTripThroughAuthorityLogTransport"
                    )
                )
            ),
            new InvariantProof(
                "ADR-0009",
                "ADR-0009",
                List.of(
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/AuthorityDomainTopologyTest.java",
                        "domainTopologyBindsKafkaTopicFamiliesToConsumerGroups"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/AuthorityLogTopologyTest.java",
                        "topologyDeclaresDocumentedTopicFamilies"
                    ),
                    proof(
                        "registry-service/src/test/java/sh/harold/fulcrum/registry/RegistryServiceRestoreReadbackTest.java",
                        "startupReceiptCapturesRestoreBudgetCustodyAndDispatcherEvidence"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/DataAuthorityCommandContractManifestTest.java",
                        "routePartitionKeyVectorsCoverEveryCommandType"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/DataAuthorityCommandContractManifestTest.java",
                        "contractManifestClassifiesSyncAndAsyncCommands"
                    )
                )
            ),
            new InvariantProof(
                "ADR-0010",
                "ADR-0010",
                List.of(
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/DataLayerStorePlacementArchitectureTest.java",
                        "commandContractsMatchDocumentedStorePlacements"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/events/InMemoryAuthorityHotStateProjectionTest.java",
                        "manifestDeclaresHotStateEventSurface"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/events/CassandraAuthorityHotStateProjectionTest.java",
                        "dispatchMatchEventWritesReducedStateRecord"
                    )
                )
            ),
            new InvariantProof(
                "ADR-0011",
                "ADR-0011",
                List.of(
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/CachedAuthorityCommandPortTest.java",
                        "repeatedCommandWithSameFingerprintReturnsCachedResult"
                    ),
                    proof(
                        "data-api/src/test/java/sh/harold/fulcrum/api/data/impl/authority/CachedAuthorityCommandPortTest.java",
                        "cacheWriteFailureStillReturnsDurableResultAndDoesNotPoisonInFlight"
                    ),
                    proof(
                        "registry-service/src/test/java/sh/harold/fulcrum/registry/authority/ValkeyAuthorityCommandResultCacheTest.java",
                        "declaresValkeyStoreOverRedisCompatibleWireProtocol"
                    ),
                    proof(
                        "registry-service/src/test/java/sh/harold/fulcrum/registry/authority/ValkeyAuthorityCommandResultCacheTest.java",
                        "writeScriptPreservesDedupeAndTtlSemantics"
                    )
                )
            )
        );
    }

    private static ProofMethod proof(String sourcePath, String methodName) {
        return new ProofMethod(sourcePath, methodName);
    }

    private static boolean assertProofMethod(String invariantId, ProofMethod proof) {
        Path sourcePath = repoPath(proof.sourcePath());
        assertThat(sourcePath)
            .as(invariantId + " proof source " + proof.sourcePath())
            .exists();
        String source = readPath(sourcePath);
        int methodIndex = source.indexOf("void " + proof.methodName() + "(");
        assertThat(methodIndex)
            .as(invariantId + " proof method " + proof.sourcePath() + "#" + proof.methodName())
            .isGreaterThanOrEqualTo(0);
        String classPreamble = source.substring(0, Math.max(0, source.indexOf("class ")));
        assertThat(classPreamble)
            .as(invariantId + " proof class is not disabled")
            .doesNotContain("@Disabled");
        String methodPreamble = source.substring(Math.max(0, methodIndex - 240), methodIndex);
        assertThat(methodPreamble)
            .as(invariantId + " proof method is a JUnit test")
            .contains("@Test")
            .doesNotContain("@Disabled");
        return classPreamble.contains("@Tag(\"live-postgres\")")
            || classPreamble.contains("@Testcontainers")
            && classPreamble.contains("disabledWithoutDocker = true");
    }

    private static String readRepoFile(String relativePath) {
        return readPath(repoPath(relativePath));
    }

    private static String readRootRepoFile(String relativePath) {
        Path workingDirectory = Path.of("").toAbsolutePath();
        for (Path base : List.of(workingDirectory, workingDirectory.getParent())) {
            if (base == null || !Files.exists(base.resolve("settings.gradle.kts"))) {
                continue;
            }
            Path candidate = base.resolve(relativePath);
            if (Files.exists(candidate)) {
                return readPath(candidate);
            }
        }
        throw new IllegalStateException("Root repo file not found from " + workingDirectory + ": " + relativePath);
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

    private static String propertyValue(String properties, String key) {
        String prefix = key + "=";
        return properties.lines()
            .filter(line -> line.startsWith(prefix))
            .map(line -> line.substring(prefix.length()).trim())
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Property not found: " + key));
    }

    private static String readResource(String resourcePath) {
        try (InputStream stream = DataLayerAdrConformanceTest.class.getClassLoader()
            .getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalArgumentException("Resource not found: " + resourcePath);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read resource: " + resourcePath, exception);
        }
    }

    private static Pattern createTablePattern(String tableName) {
        return Pattern.compile(
            "(?is)\\bCREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+" + Pattern.quote(tableName) + "\\b"
        );
    }

    private record AdrEvidenceHook(
        String tableName,
        String dataOwner,
        String readerService,
        String writerService,
        String documentedNeedle,
        boolean lifecyclePolicyRequired
    ) {
    }

    private record InvariantProof(
        String id,
        String documentedNeedle,
        List<ProofMethod> methods
    ) {
    }

    private record ProofMethod(
        String sourcePath,
        String methodName
    ) {
    }
}
