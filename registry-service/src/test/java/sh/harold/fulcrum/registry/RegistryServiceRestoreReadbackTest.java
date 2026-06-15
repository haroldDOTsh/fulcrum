package sh.harold.fulcrum.registry;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.impl.authority.DataAuthorityCustodyPreflight;
import sh.harold.fulcrum.api.data.impl.authority.events.InMemoryAuthorityHotStateProjection;
import sh.harold.fulcrum.api.data.impl.postgres.FulcrumDataMigrations;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionBudget;
import sh.harold.fulcrum.registry.allocation.IdAllocator;
import sh.harold.fulcrum.registry.authority.AuthoritySubstratePreflight;
import sh.harold.fulcrum.registry.persistence.RegistryNodeSnapshot;
import sh.harold.fulcrum.registry.persistence.RegistryNodeSnapshotStore;
import sh.harold.fulcrum.registry.proxy.ProxyRegistry;
import sh.harold.fulcrum.registry.proxy.RegisteredProxyData;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.server.ServerRegistry;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RegistryServiceRestoreReadbackTest {
    @Test
    void restoreRegistrySnapshotsReportsReadableBackendAndProxyReservations() {
        RegistryNodeSnapshot backend = new RegistryNodeSnapshot(
            "mini9",
            "BACKEND",
            "127.0.0.1",
            25565,
            "game",
            RegisteredServerData.Status.RUNNING.name(),
            40,
            Map.of("tempId", "temp-mini-9", "serverType", "mini"),
            Instant.now().minusSeconds(90),
            Instant.now().minusSeconds(30)
        ).withAttestation(UUID.randomUUID(), "registry-test");
        String proxyId = "proxy-00000000-0000-0000-0000-000000000009-1-1700000000000";
        RegistryNodeSnapshot proxy = new RegistryNodeSnapshot(
            proxyId,
            "PROXY",
            "127.0.0.1",
            25577,
            "proxy",
            "AVAILABLE",
            0,
            Map.of(),
            Instant.now().minusSeconds(120),
            Instant.now().minusSeconds(40)
        ).withAttestation(UUID.randomUUID(), "registry-test");

        ServerRegistry serverRegistry = new ServerRegistry(new IdAllocator(false));
        ProxyRegistry proxyRegistry = new ProxyRegistry(new IdAllocator(false), false);
        try {
            RegistryService.RegistrySnapshotRestoreReport report = RegistryService.restoreRegistrySnapshots(
                new SnapshotStore(List.of(backend, proxy)),
                serverRegistry,
                proxyRegistry
            );

            assertThat(report.loadedSnapshots()).isEqualTo(2);
            assertThat(report.validAttestedSnapshots()).isEqualTo(2);
            assertThat(report.invalidAttestations()).isZero();
            assertThat(report.restoredBackends()).isEqualTo(1);
            assertThat(report.backendReadbacks()).isEqualTo(1);
            assertThat(report.restoredProxyReservations()).isEqualTo(1);
            assertThat(report.proxyReservationReadbacks()).isEqualTo(1);
            assertThat(report.readbackClean()).isTrue();
            assertThat(report.snapshotSources()).containsExactly("registry-test");
            assertThat(report.schemaEvidence().enabled()).isFalse();

            assertThat(serverRegistry.getServer("mini9")).isNotNull();
            assertThat(proxyRegistry.getPermanentId(proxyId)).isEqualTo(proxyId);
            assertThat(proxyRegistry.getProxyIdByAddress("127.0.0.1", 25577)).isEqualTo(proxyId);
            assertThat(proxyRegistry.getUnavailableProxyCount()).isEqualTo(1);
        } finally {
            proxyRegistry.shutdown();
        }
    }

    @Test
    void startupReceiptCapturesRestoreBudgetCustodyAndDispatcherEvidence() {
        RegistryService.RegistrySnapshotRestoreReport restoreReport =
            new RegistryService.RegistrySnapshotRestoreReport(
                2,
                2,
                0,
                1,
                1,
                1,
                1,
                List.of("registry-test"),
                snapshotSchemaEvidence()
            );
        PostgresConnectionBudget.Report budget = PostgresConnectionBudget.inspect(
            List.of(PostgresConnectionBudget.declaration(
                "registry-service:central-authority",
                "registry-service",
                PostgresConnectionBudget.REGISTRY_SERVICE_BOUNDARY,
                "FulcrumPostgresPool-authority-fulcrum",
                4,
                1,
                5000L
            )),
            8
        );
        DataAuthorityCustodyPreflight.Report custody = DataAuthorityCustodyPreflight.inspect(
            "registry-test",
            "message-bus-provider",
            List.of(
                DataAuthorityCustodyPreflight.check("postgres-data-authority-schema", () -> {
                }),
                DataAuthorityCustodyPreflight.check("authority-command-log-schema", () -> {
                })
            )
        );

        RegistryService.RegistryStartupReceipt receipt = RegistryService.createStartupReceipt(
            Instant.parse("2026-06-13T00:00:00Z"),
            "registry-test",
            "REDIS",
            restoreReport,
            budget,
            RegistryService.AuthorityStartupState.from(custody, targetSubstrateReport()),
            RegistryService.DispatcherStartupState.enabled(
                InMemoryAuthorityHotStateProjection.PROJECTION_NAME,
                10,
                250L,
                1000L
            )
        );

        assertThat(receipt.snapshotRestore().readbackClean()).isTrue();
        assertThat(receipt.snapshotRestore().snapshotSources()).containsExactly("registry-test");
        assertThat(receipt.snapshotRestore().schemaEvidence().enabled()).isTrue();
        assertThat(receipt.snapshotRestore().schemaEvidence().contractVersion()).isEqualTo(3);
        assertThat(receipt.snapshotRestore().schemaEvidence().ddlOwner()).isEqualTo("data-api");
        assertThat(receipt.snapshotRestore().schemaEvidence().schemaMigrationResource())
            .isEqualTo(FulcrumDataMigrations.SCHEMA_MIGRATION);
        assertThat(receipt.postgresConnectionBudget().fingerprint()).isEqualTo(budget.fingerprint());
        assertThat(receipt.postgresConnectionBudget().accepted()).isTrue();
        assertThat(receipt.authority().enabled()).isTrue();
        assertThat(receipt.authority().preflightPassed()).isTrue();
        assertThat(receipt.authority().custodyFingerprint()).isEqualTo(custody.custodyFingerprint());
        assertThat(receipt.authority().checkNames())
            .containsExactly("authority-command-log-schema", "postgres-data-authority-schema");
        assertThat(receipt.authority().substrateMode()).isEqualTo("target");
        assertThat(receipt.authority().substrateTargetComplete()).isTrue();
        assertThat(receipt.authority().substrateCommandLog()).isEqualTo("kafka");
        assertThat(receipt.authority().substrateHotState()).isEqualTo("cassandra");
        assertThat(receipt.authority().substrateHistory()).isEqualTo("postgresql");
        assertThat(receipt.authority().substrateCache()).isEqualTo("valkey");
        assertThat(receipt.authority().substrateLimitations()).isEmpty();
        assertThat(receipt.dispatcher().enabled()).isTrue();
        assertThat(receipt.dispatcher().schemaValidated()).isTrue();
        assertThat(receipt.dispatcher().scheduled()).isTrue();
        assertThat(receipt.dispatcher().batchSize()).isEqualTo(10);
        assertThat(receipt.dispatcher().intervalMs()).isEqualTo(250L);
        assertThat(receipt.dispatcher().retryDelayMs()).isEqualTo(1000L);
        assertThat(receipt.fingerprint()).isNotBlank();
        assertThat(receipt.summary())
            .contains("registryNodeId=registry-test")
            .contains("readbackClean=true")
            .contains("contractVersion=3")
            .contains("ddlOwner=data-api")
            .contains("schemaMigrationResource=" + FulcrumDataMigrations.SCHEMA_MIGRATION)
            .contains("consumerName=" + InMemoryAuthorityHotStateProjection.PROJECTION_NAME)
            .doesNotContain("jdbc", "password", "username");

        assertThat(RegistryService.authorityStatusLines(
            receipt,
            RegistryService.AuthorityStartupState.disabled("missing"),
            RegistryService.DispatcherStartupState.disabled("missing")
        ))
            .contains(
                "Enabled: true",
                "Owner Node: registry-test",
                "Principal Source: message-bus-provider",
                "Preflight Passed: true",
                "Substrate Mode: target",
                "Substrate Target Complete: true",
                "Substrate Command Log: kafka",
                "Substrate Hot State: cassandra",
                "Substrate History: postgresql",
                "Substrate Cache: valkey",
                "Checks: [authority-command-log-schema, postgres-data-authority-schema]",
                "Dispatcher: enabled, consumer=" + InMemoryAuthorityHotStateProjection.PROJECTION_NAME
                    + ", batchSize=10, intervalMs=250"
            )
            .anySatisfy(line -> assertThat(line).startsWith("Startup Receipt: "))
            .anySatisfy(line -> assertThat(line).startsWith("Custody Fingerprint: "));
    }

    @Test
    void startupReceiptFingerprintIgnoresCreationTimeButTracksEvidenceChanges() {
        RegistryService.RegistrySnapshotRestoreReport restoreReport =
            new RegistryService.RegistrySnapshotRestoreReport(
                1,
                1,
                0,
                1,
                0,
                1,
                0,
                List.of("registry-test"),
                snapshotSchemaEvidence()
            );
        PostgresConnectionBudget.Report budget = PostgresConnectionBudget.inspect(List.of(), 8);
        RegistryService.AuthorityStartupState authority =
            RegistryService.AuthorityStartupState.disabled("authority-disabled");
        RegistryService.DispatcherStartupState dispatcher =
            RegistryService.DispatcherStartupState.disabled("authority-disabled");

        RegistryService.RegistryStartupReceipt first = RegistryService.createStartupReceipt(
            Instant.parse("2026-06-13T00:00:00Z"),
            "registry-test",
            "IN_MEMORY",
            restoreReport,
            budget,
            authority,
            dispatcher
        );
        RegistryService.RegistryStartupReceipt sameEvidence = RegistryService.createStartupReceipt(
            Instant.parse("2026-06-13T00:01:00Z"),
            "registry-test",
            "IN_MEMORY",
            restoreReport,
            budget,
            authority,
            dispatcher
        );
        RegistryService.RegistryStartupReceipt changedDispatcher = RegistryService.createStartupReceipt(
            Instant.parse("2026-06-13T00:00:00Z"),
            "registry-test",
            "IN_MEMORY",
            restoreReport,
            budget,
            authority,
            RegistryService.DispatcherStartupState.enabled(
                InMemoryAuthorityHotStateProjection.PROJECTION_NAME,
                50,
                1000L,
                5000L
            )
        );
        RegistryService.RegistryStartupReceipt changedAuthoritySubstrate = RegistryService.createStartupReceipt(
            Instant.parse("2026-06-13T00:00:00Z"),
            "registry-test",
            "IN_MEMORY",
            restoreReport,
            budget,
            RegistryService.AuthorityStartupState.from(
                DataAuthorityCustodyPreflight.inspect(
                    "registry-test",
                    "message-bus-provider",
                    List.of(DataAuthorityCustodyPreflight.check("postgres-data-authority-schema", () -> {
                    }))
                ),
                AuthoritySubstratePreflight.inspect(
                    Map.of(
                        "substrate", Map.of(
                            "mode", "target",
                            "command-log", "kafka",
                            "hot-state", "cassandra",
                            "history", "postgresql",
                            "cache", "valkey"
                        )
                    ),
                    new AuthoritySubstratePreflight.ActualSubstrate(
                        "kafka",
                        "cassandra",
                        "postgresql",
                        "valkey"
                    )
                )
            ),
            dispatcher
        );
        RegistryService.RegistryStartupReceipt changedSchema = RegistryService.createStartupReceipt(
            Instant.parse("2026-06-13T00:00:00Z"),
            "registry-test",
            "IN_MEMORY",
            new RegistryService.RegistrySnapshotRestoreReport(
                restoreReport.loadedSnapshots(),
                restoreReport.validAttestedSnapshots(),
                restoreReport.invalidAttestations(),
                restoreReport.restoredBackends(),
                restoreReport.restoredProxyReservations(),
                restoreReport.backendReadbacks(),
                restoreReport.proxyReservationReadbacks(),
                restoreReport.snapshotSources(),
                RegistryNodeSnapshotStore.SnapshotSchemaEvidence.enabled(
                    3,
                    "changed-schema-fingerprint",
                    "registry_node_snapshots",
                    "data-api",
                    "registry-control-plane",
                    FulcrumDataMigrations.SCHEMA_MIGRATION,
                    "001",
                    FulcrumDataMigrations.SCHEMA_MIGRATION,
                    FulcrumDataMigrations.SCHEMA_MIGRATION
                )
            ),
            budget,
            authority,
            dispatcher
        );

        assertThat(sameEvidence.fingerprint()).isEqualTo(first.fingerprint());
        assertThat(changedDispatcher.fingerprint()).isNotEqualTo(first.fingerprint());
        assertThat(changedAuthoritySubstrate.fingerprint()).isNotEqualTo(first.fingerprint());
        assertThat(changedSchema.fingerprint()).isNotEqualTo(first.fingerprint());
        assertThat(first.authority().enabled()).isFalse();
        assertThat(first.authority().disabledReason()).isEqualTo("authority-disabled");
        assertThat(first.dispatcher().enabled()).isFalse();
        assertThat(first.summary()).contains("disabledReason=authority-disabled");
    }

    @Test
    void authorityEnabledConfigReportsExternalizedStartupStatus() {
        String disabledReason = RegistryService.authorityStartupDisabledReason(Map.of("enabled", true));

        assertThat(disabledReason).isEqualTo("authority-externalized");
        assertThat(RegistryService.authorityStatusLines(
            null,
            RegistryService.AuthorityStartupState.disabled(disabledReason),
            RegistryService.DispatcherStartupState.disabled(disabledReason)
        ))
            .contains(
                "Startup Receipt: unavailable",
                "Enabled: false",
                "Disabled Reason: authority-externalized",
                "Dispatcher: disabled (authority-externalized)"
            );
        assertThat(RegistryService.authorityStartupDisabledReason(Map.of("enabled", false)))
            .isEqualTo("authority-disabled");
    }

    private static RegistryNodeSnapshotStore.SnapshotSchemaEvidence snapshotSchemaEvidence() {
        return RegistryNodeSnapshotStore.SnapshotSchemaEvidence.enabled(
            3,
            "schema-contract-fingerprint",
            "registry_node_snapshots",
            "data-api",
            "registry-control-plane",
            FulcrumDataMigrations.SCHEMA_MIGRATION,
            "001",
            FulcrumDataMigrations.SCHEMA_MIGRATION,
            FulcrumDataMigrations.SCHEMA_MIGRATION
        );
    }

    private static AuthoritySubstratePreflight.Report targetSubstrateReport() {
        return AuthoritySubstratePreflight.inspect(
            Map.of(
                "substrate", Map.of(
                    "mode", "target",
                    "command-log", "kafka",
                    "hot-state", "cassandra",
                    "history", "postgresql",
                    "cache", "valkey"
                )
            ),
            new AuthoritySubstratePreflight.ActualSubstrate(
                "kafka",
                "cassandra",
                "postgresql",
                "valkey"
            )
        );
    }

    private record SnapshotStore(List<RegistryNodeSnapshot> snapshots) implements RegistryNodeSnapshotStore {
        @Override
        public List<RegistryNodeSnapshot> loadSnapshots() {
            return snapshots;
        }

        @Override
        public void snapshotServer(RegisteredServerData server) {
        }

        @Override
        public void snapshotProxy(RegisteredProxyData proxy) {
        }

        @Override
        public void markOffline(String nodeId, String nodeType, String state) {
        }
    }
}
