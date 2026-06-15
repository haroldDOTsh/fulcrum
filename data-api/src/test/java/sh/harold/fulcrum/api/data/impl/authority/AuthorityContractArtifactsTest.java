package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.postgres.FulcrumDataMigrations;
import sh.harold.fulcrum.api.data.impl.postgres.FulcrumSchemaContract;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityContractArtifactsTest {
    @Test
    void traceabilityManifestCoversExecutableContracts() {
        AuthorityContractArtifacts.TraceabilityManifest manifest =
            AuthorityContractArtifacts.traceabilityManifest();

        assertThat(manifest.schemaVersion()).isEqualTo(3);
        assertThat(manifest.schemaFingerprint()).matches("[0-9a-f]{64}");
        assertThat(manifest.commandContractFingerprint())
            .isEqualTo(DataAuthorityCommandContracts.fingerprint());
        assertThat(manifest.routeManifestFingerprint())
            .isEqualTo(DataAuthorityCommandContracts.routeManifestFingerprint());
        assertThat(manifest.domainTopologyFingerprint())
            .isEqualTo(AuthorityDomainTopology.fingerprint());
        assertThat(manifest.readContractFingerprint())
            .isEqualTo(DataAuthorityReadContracts.fingerprint());
        assertThat(manifest.storePlacementFingerprint())
            .isEqualTo(AuthorityStorePlacements.fingerprint());
        assertThat(manifest.storePlacements())
            .extracting(AuthorityStorePlacements.StorePlacement::concern)
            .containsExactlyInAnyOrderElementsOf(AuthorityStorePlacements.all().keySet());
        assertThat(manifest.domainTopologies())
            .extracting(AuthorityDomainTopology.DomainTopology::domain)
            .containsExactlyInAnyOrderElementsOf(AuthorityDomainTopology.all().keySet());
        assertThat(manifest.commands())
            .extracting(AuthorityContractArtifacts.CommandRow::declarationId)
            .containsExactlyInAnyOrderElementsOf(AuthorityDomainDeclarations.declarationIds());
        assertThat(manifest.reads())
            .extracting(AuthorityContractArtifacts.ReadRow::type)
            .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(DataAuthorityReadContracts.ReadType.class));
        assertThat(manifest.topics())
            .extracting(AuthorityContractArtifacts.TopicRow::topic)
            .containsExactlyInAnyOrderElementsOf(AuthorityLogTopology.policiesByTopic().keySet());
        assertThat(manifest.generatedArtifacts())
            .extracting(AuthorityContractArtifacts.GeneratedArtifactRow::kind)
            .contains(
                AuthorityContractArtifacts.GeneratedArtifactKind.TYPED_CLIENT,
                AuthorityContractArtifacts.GeneratedArtifactKind.COMMAND_SERIALIZER,
                AuthorityContractArtifacts.GeneratedArtifactKind.PROJECTION_WRITER,
                AuthorityContractArtifacts.GeneratedArtifactKind.SNAPSHOT_READER,
                AuthorityContractArtifacts.GeneratedArtifactKind.DDL
            );
    }

    @Test
    void traceabilityFingerprintIsStable() throws Exception {
        String actual = AuthorityContractArtifacts.traceabilityFingerprint();

        assertThat(actual).matches("[0-9a-f]{64}");
        assertThat(actual).isEqualTo(goldenFingerprint("/contracts/data-authority-traceability-manifest.sha256"));
    }

    @Test
    void grantRankRowTiesCommandToRouteStoresAndSchemaTables() {
        AuthorityContractArtifacts.CommandRow row = command(DataAuthority.CommandType.GRANT_RANK);

        assertThat(row.domain()).isEqualTo("rank");
        assertThat(row.deliveryMode()).isEqualTo(DataAuthorityCommandContracts.CommandDeliveryMode.SYNC_INTERACTIVE);
        assertThat(row.revisionPolicy()).isEqualTo(DataAuthorityCommandContracts.CommandRevisionPolicy.COMPARE_REQUIRED);
        assertThat(row.aggregateScopeTemplate()).isEqualTo("rank:player:{aggregateId}");
        assertThat(row.partitionKeyTemplate()).isEqualTo("rank:player:{aggregateId}");
        assertThat(row.commandTopic()).isEqualTo("cmd.rank");
        assertThat(row.responseTopic()).isEqualTo("rsp.rank");
        assertThat(row.eventTopic()).isEqualTo("evt.rank");
        assertThat(row.stateTopic()).isEqualTo("state.rank");
        assertThat(row.partitionCount()).isEqualTo(AuthorityCommandLane.DEFAULT_LANE_COUNT);
        assertThat(row.commandRetentionClass()).isEqualTo("retained-days");
        assertThat(row.stateCompacted()).isTrue();
        assertThat(row.stateRetentionClass()).isEqualTo("compacted-forever");
        assertThat(row.commandLogStore()).isEqualTo("kafka");
        assertThat(row.hotProjectionStore()).isEqualTo("cassandra");
        assertThat(row.historyStore()).isEqualTo("postgresql");
        assertThat(row.cacheStore()).isEqualTo("valkey");
        assertThat(tableNames(row.schemaTables())).contains(
            "authority_commands",
            "authority_events",
            "authority_state_snapshots",
            "authority_state_changelog",
            "authority_partition_epochs",
            "authority_writer_claims",
            "player_rank_audit"
        );
        assertThat(tableOwners(row.schemaTables()))
            .containsEntry("player_rank_audit", "authority-rank")
            .containsEntry("authority_state_changelog", "authority-state-log");
    }

    @Test
    void readRowsExposeServingStoresStateTopicsAndSchemaTables() {
        AuthorityContractArtifacts.ReadRow profile =
            read(DataAuthorityReadContracts.ReadType.PLAYER_PROFILE);
        AuthorityContractArtifacts.ReadRow rank =
            read(DataAuthorityReadContracts.ReadType.PLAYER_RANK);

        assertThat(profile.projectionFamily()).isEqualTo("player_profile");
        assertThat(profile.servingStore()).isEqualTo("postgresql-read-replica");
        assertThat(profile.cacheStore()).isEqualTo("valkey");
        assertThat(profile.expectedStateTopics()).containsExactly("state.player_profile", "state.player", "state.session");
        assertThat(tableNames(profile.schemaTables()))
            .containsExactlyInAnyOrder("player_profiles", "authority_state_snapshots");

        assertThat(rank.projectionFamily()).isEqualTo("player_rank");
        assertThat(rank.servingStore()).isEqualTo("cassandra");
        assertThat(rank.cacheStore()).isEqualTo("valkey");
        assertThat(rank.expectedStateTopics()).containsExactly("state.player_rank", "state.rank");
        assertThat(tableNames(rank.schemaTables()))
            .containsExactlyInAnyOrder("authority_state_snapshots");
    }

    @Test
    void generatedArtifactsCoverAdr0008CodegenOutputs() {
        AuthorityContractArtifacts.TraceabilityManifest manifest =
            AuthorityContractArtifacts.traceabilityManifest();
        Set<String> artifactIds = manifest.generatedArtifacts().stream()
            .map(AuthorityContractArtifacts.GeneratedArtifactRow::artifactId)
            .collect(Collectors.toUnmodifiableSet());

        for (String declarationId : AuthorityDomainDeclarations.declarationIds()) {
            assertThat(artifactIds).contains(
                "typed-client/command/" + declarationId,
                "command-serializer/" + declarationId,
                "projection-writer/" + declarationId
            );
        }
        for (DataAuthorityReadContracts.ReadType type : EnumSet.allOf(DataAuthorityReadContracts.ReadType.class)) {
            assertThat(artifactIds).contains(
                "typed-client/read/" + type,
                "snapshot-reader/" + type
            );
        }

        Set<String> ddlTables = manifest.generatedArtifacts().stream()
            .filter(artifact -> artifact.kind() == AuthorityContractArtifacts.GeneratedArtifactKind.DDL)
            .flatMap(artifact -> artifact.schemaTables().stream())
            .collect(Collectors.toUnmodifiableSet());
        assertThat(ddlTables).containsExactlyInAnyOrderElementsOf(FulcrumSchemaContract.loadDefault().tableNames());
        for (AuthorityContractArtifacts.GeneratedArtifactRow artifact : manifest.generatedArtifacts().stream()
            .filter(artifact -> artifact.kind() == AuthorityContractArtifacts.GeneratedArtifactKind.DDL)
            .toList()) {
            assertThat(artifact.outputPath()).as(artifact.artifactId())
                .startsWith("data-api/src/main/resources/migrations/")
                .contains("#" + artifact.schemaTables().get(0));
            assertThat(artifact.generatedFrom()).as(artifact.artifactId())
                .contains("ddl-owner:data-api")
                .anyMatch(value -> value.startsWith("created-by:migrations/"));
        }

        for (AuthorityContractArtifacts.GeneratedArtifactRow artifact : manifest.generatedArtifacts()) {
            assertThat(artifact.outputPath()).as(artifact.artifactId()).isNotBlank();
            assertThat(artifact.sourceFingerprint()).as(artifact.artifactId()).matches("[0-9a-f]{64}");
            assertThat(artifact.generatedFrom()).as(artifact.artifactId()).isNotEmpty();
            if (artifact.kind() != AuthorityContractArtifacts.GeneratedArtifactKind.DDL) {
                assertThat(artifact.schemaTables()).as(artifact.artifactId()).isNotEmpty();
            }
        }
    }

    @Test
    void schemaTableRefsCarryDdlOwnerAndCreatingMigration() {
        AuthorityContractArtifacts.TraceabilityManifest manifest =
            AuthorityContractArtifacts.traceabilityManifest();

        for (AuthorityContractArtifacts.CommandRow row : manifest.commands()) {
            assertSchemaTableRefs(row.schemaTables());
        }
        for (AuthorityContractArtifacts.ReadRow row : manifest.reads()) {
            assertSchemaTableRefs(row.schemaTables());
        }
    }

    @Test
    void topicRowsMatchExecutableTopologyPolicies() {
        Map<String, AuthorityLogTopicPolicy> policies = AuthorityLogTopology.policiesByTopic();

        for (AuthorityContractArtifacts.TopicRow row : AuthorityContractArtifacts.traceabilityManifest().topics()) {
            AuthorityLogTopicPolicy policy = policies.get(row.topic());
            assertThat(policy).as(row.topic()).isNotNull();
            assertThat(row.kind()).as(row.topic()).isEqualTo(policy.kind());
            assertThat(row.domain()).as(row.topic()).isEqualTo(policy.domain());
            assertThat(row.partitionCount()).as(row.topic()).isEqualTo(policy.partitionCount());
        assertThat(row.compacted()).as(row.topic()).isEqualTo(policy.compacted());
        assertThat(row.retentionClass()).as(row.topic()).isEqualTo(policy.retentionClass());
        assertThat(row.keyRule()).as(row.topic()).isEqualTo(policy.keyRule());
        assertThat(row.producerPrincipalPatterns()).as(row.topic())
            .containsExactlyElementsOf(policy.producerPrincipalPatterns());
        assertThat(row.consumerPrincipalPatterns()).as(row.topic())
            .containsExactlyElementsOf(policy.consumerPrincipalPatterns());
    }
    }

    @Test
    void domainTopologyRowsMatchExecutableAuthorityTopology() {
        Map<String, AuthorityDomainTopology.DomainTopology> topologies = AuthorityDomainTopology.all();

        for (AuthorityDomainTopology.DomainTopology row
            : AuthorityContractArtifacts.traceabilityManifest().domainTopologies()) {
            AuthorityDomainTopology.DomainTopology topology = topologies.get(row.domain());
            assertThat(topology).as(row.domain()).isNotNull();
            assertThat(row.consumerGroup()).as(row.domain()).isEqualTo(topology.consumerGroup());
            assertThat(row.authorityPrincipal()).as(row.domain()).isEqualTo(topology.authorityPrincipal());
            assertThat(row.partitionCount()).as(row.domain()).isEqualTo(topology.partitionCount());
            assertThat(row.commandTypes()).as(row.domain()).containsExactlyElementsOf(topology.commandTypes());
            assertThat(row.allTopics()).as(row.domain()).containsExactlyElementsOf(topology.allTopics());
            assertThat(row.allStores()).as(row.domain()).containsExactlyElementsOf(topology.allStores());
        }
    }

    @Test
    void commandRowsReferenceDocumentedStorePlacements() {
        for (AuthorityContractArtifacts.CommandRow row : AuthorityContractArtifacts.traceabilityManifest().commands()) {
            assertThat(storesFor(AuthorityStorePlacements.COMMAND_AUDIT))
                .as(row.type() + " command log store")
                .contains(row.commandLogStore());
            assertThat(storesFor(hotConcern(row.type())))
                .as(row.type() + " hot projection store")
                .contains(row.hotProjectionStore());
            assertThat(storesFor(historyConcern(row.type())))
                .as(row.type() + " history store")
                .contains(row.historyStore());
            assertThat(storesFor(AuthorityStorePlacements.SNAPSHOT_CACHE))
                .as(row.type() + " cache store")
                .contains(row.cacheStore());
        }
    }

    @Test
    void readRowsReferenceDocumentedStorePlacements() {
        for (AuthorityContractArtifacts.ReadRow row : AuthorityContractArtifacts.traceabilityManifest().reads()) {
            assertThat(storesFor(readConcern(row.type())))
                .as(row.type() + " serving store")
                .contains(normalizedStore(row.servingStore()));
            assertThat(storesFor(AuthorityStorePlacements.SNAPSHOT_CACHE))
                .as(row.type() + " cache store")
                .contains(row.cacheStore());
        }
    }

    private static AuthorityContractArtifacts.CommandRow command(DataAuthority.CommandType type) {
        return AuthorityContractArtifacts.traceabilityManifest().commands().stream()
            .filter(row -> row.type() == type)
            .findFirst()
            .orElseThrow();
    }

    private static AuthorityContractArtifacts.ReadRow read(DataAuthorityReadContracts.ReadType type) {
        return AuthorityContractArtifacts.traceabilityManifest().reads().stream()
            .filter(row -> row.type() == type)
            .findFirst()
            .orElseThrow();
    }

    private static Set<String> tableNames(
        java.util.List<AuthorityContractArtifacts.SchemaTableRef> tables
    ) {
        return tables.stream()
            .map(AuthorityContractArtifacts.SchemaTableRef::tableName)
            .collect(Collectors.toUnmodifiableSet());
    }

    private static Map<String, String> tableOwners(
        java.util.List<AuthorityContractArtifacts.SchemaTableRef> tables
    ) {
        return tables.stream().collect(Collectors.toUnmodifiableMap(
            AuthorityContractArtifacts.SchemaTableRef::tableName,
            AuthorityContractArtifacts.SchemaTableRef::dataOwner
        ));
    }

    private static String goldenFingerprint(String path) throws Exception {
        try (InputStream input = AuthorityContractArtifactsTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new AssertionError("Missing golden contract fingerprint resource " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    private static Set<String> storesFor(String concern) {
        return AuthorityStorePlacements.placement(concern).allStores().stream()
            .map(AuthorityContractArtifactsTest::normalizedStore)
            .collect(Collectors.toUnmodifiableSet());
    }

    private static void assertSchemaTableRefs(
        java.util.List<AuthorityContractArtifacts.SchemaTableRef> tables
    ) {
        for (AuthorityContractArtifacts.SchemaTableRef table : tables) {
            assertThat(table.ddlOwner()).as(table.tableName()).isEqualTo("data-api");
            assertThat(table.createdBy()).as(table.tableName()).isIn(FulcrumDataMigrations.all());
            assertThat(table.readers()).as(table.tableName()).isNotEmpty();
            assertThat(table.writers()).as(table.tableName()).isNotEmpty();
        }
    }

    private static String hotConcern(DataAuthority.CommandType type) {
        return switch (type) {
            case RECORD_PLAYER_LOGIN, RECORD_PLAYER_LOGOUT, START_SESSION, RENEW_SESSION, END_SESSION ->
                AuthorityStorePlacements.PLAYER_PRESENCE;
            case GRANT_RANK, REVOKE_RANK -> AuthorityStorePlacements.LIVE_EFFECTIVE_RANKS;
            case RECORD_MATCH_START, RECORD_MATCH_END -> AuthorityStorePlacements.LIVE_MATCH_STATE;
        };
    }

    private static String historyConcern(DataAuthority.CommandType type) {
        return switch (type) {
            case RECORD_PLAYER_LOGIN, RECORD_PLAYER_LOGOUT ->
                AuthorityStorePlacements.PLAYER_PROFILE_OF_RECORD;
            case START_SESSION, RENEW_SESSION, END_SESSION -> AuthorityStorePlacements.SESSION_HISTORY;
            case GRANT_RANK, REVOKE_RANK -> AuthorityStorePlacements.RANK_HISTORY_AUDIT;
            case RECORD_MATCH_START, RECORD_MATCH_END -> AuthorityStorePlacements.MATCH_HISTORY_STATS;
        };
    }

    private static String readConcern(DataAuthorityReadContracts.ReadType type) {
        return switch (type) {
            case PLAYER_PROFILE -> AuthorityStorePlacements.PLAYER_PROFILE_OF_RECORD;
            case PLAYER_RANK -> AuthorityStorePlacements.LIVE_EFFECTIVE_RANKS;
        };
    }

    private static String normalizedStore(String store) {
        return store.startsWith("postgresql") ? "postgresql" : store;
    }
}
