package sh.harold.fulcrum.registry.persistence;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityDomainTopology;
import sh.harold.fulcrum.api.data.impl.authority.DataAuthorityCommandContracts;
import sh.harold.fulcrum.api.data.impl.authority.DataAuthorityReadContracts;
import sh.harold.fulcrum.api.data.impl.postgres.FulcrumDataMigrations;
import sh.harold.fulcrum.api.data.impl.postgres.FulcrumSchemaContract;
import sh.harold.fulcrum.api.messagebus.messages.RuntimeAuthorityDeliveryManifest;
import sh.harold.fulcrum.api.messagebus.messages.RuntimeDataAuthorityAttestation;
import sh.harold.fulcrum.api.messagebus.messages.SlotLifecycleStatus;
import sh.harold.fulcrum.api.messagebus.messages.SlotStatusUpdateMessage;
import sh.harold.fulcrum.registry.allocation.IdAllocator;
import sh.harold.fulcrum.registry.messages.RegistrationRequest;
import sh.harold.fulcrum.registry.proxy.ProxyIdentifier;
import sh.harold.fulcrum.registry.proxy.ProxyRegistry;
import sh.harold.fulcrum.registry.proxy.RegisteredProxyData;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.server.ServerRegistry;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistryNodeSnapshotStoreWiringTest {

    @Test
    void registrySnapshotStoreRequiresDataApiSchemaContract() {
        PostgresRegistryNodeSnapshotStore.validateSchemaContract(FulcrumSchemaContract.loadDefault());

        FulcrumSchemaContract.TableContract table = FulcrumSchemaContract.loadDefault()
            .table("registry_node_snapshots");
        assertThat(table.ddlOwner()).isEqualTo("data-api");
        assertThat(table.canRead("registry-service")).isTrue();
        assertThat(table.canWrite("registry-service")).isTrue();
    }

    @Test
    void registrySnapshotStoreRejectsSchemaContractWithoutWriterGrant() {
        FulcrumSchemaContract contract = FulcrumSchemaContract.load(new ByteArrayInputStream("""
            schema.version=1
            tables=registry_node_snapshots
            table.registry_node_snapshots.ddl-owner=data-api
            table.registry_node_snapshots.data-owner=registry-control-plane
            table.registry_node_snapshots.created-by=%s
            table.registry_node_snapshots.readers=registry-service
            table.registry_node_snapshots.writers=data-api
            """.formatted(FulcrumDataMigrations.SCHEMA_MIGRATION).getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> PostgresRegistryNodeSnapshotStore.validateSchemaContract(contract))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("registry_node_snapshots")
            .hasMessageContaining("registry-service write access");
    }

    @Test
    void registrySnapshotSchemaReadinessAcceptsMigrationOwnedAttestationShape() {
        PostgresRegistryNodeSnapshotStore.validateSnapshotSchemaObservation(
            new PostgresRegistryNodeSnapshotStore.SnapshotSchemaObservation(
                registrySnapshotColumns(),
                Set.of("registry_node_snapshots_pkey", "idx_registry_node_snapshots_attestation"),
                FulcrumDataMigrations.SCHEMA_MIGRATION
            )
        );
    }

    @Test
    void registrySnapshotSchemaReadinessRejectsMissingAttestationColumn() {
        Set<String> columns = registrySnapshotColumns();
        columns.remove("snapshot_fingerprint");

        assertThatThrownBy(() -> PostgresRegistryNodeSnapshotStore.validateSnapshotSchemaObservation(
            new PostgresRegistryNodeSnapshotStore.SnapshotSchemaObservation(
                columns,
                Set.of("registry_node_snapshots_pkey", "idx_registry_node_snapshots_attestation"),
                FulcrumDataMigrations.SCHEMA_MIGRATION
            )
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("snapshot_fingerprint")
            .hasMessageContaining(FulcrumDataMigrations.SCHEMA_MIGRATION);
    }

    @Test
    void registrySnapshotSchemaReadinessRejectsMissingAttestationIndex() {
        assertThatThrownBy(() -> PostgresRegistryNodeSnapshotStore.validateSnapshotSchemaObservation(
            new PostgresRegistryNodeSnapshotStore.SnapshotSchemaObservation(
                registrySnapshotColumns(),
                Set.of("registry_node_snapshots_pkey"),
                FulcrumDataMigrations.SCHEMA_MIGRATION
            )
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("idx_registry_node_snapshots_attestation")
            .hasMessageContaining(FulcrumDataMigrations.SCHEMA_MIGRATION);
    }

    @Test
    void registrySnapshotSchemaReadinessRejectsMissingMigrationReceipt() {
        assertThatThrownBy(() -> PostgresRegistryNodeSnapshotStore.validateSnapshotSchemaObservation(
            new PostgresRegistryNodeSnapshotStore.SnapshotSchemaObservation(
                registrySnapshotColumns(),
                Set.of("registry_node_snapshots_pkey", "idx_registry_node_snapshots_attestation"),
                null
            )
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("migration receipt 001")
            .hasMessageContaining("Run data-api migrations");
    }

    @Test
    void serverRegistryEmitsSnapshotsForLifecycleChanges() {
        RecordingSnapshotStore snapshots = new RecordingSnapshotStore();
        ServerRegistry registry = new ServerRegistry(new IdAllocator(false));
        registry.setSnapshotStore(snapshots);

        String serverId = registry.registerServer(registration("temp-mini-1"));
        registry.updateStatus(serverId, RegisteredServerData.Status.AVAILABLE.name());
        registry.deregisterServer(serverId);

        assertThat(snapshots.serverStates).containsExactly(
            RegisteredServerData.Status.STARTING.name(),
            RegisteredServerData.Status.AVAILABLE.name()
        );
        assertThat(snapshots.offlineNodes).containsExactly(serverId + ":BACKEND:DEAD");
    }

    @Test
    void serverRegistryStoresDataAuthorityAttestationFromRegistration() {
        RecordingSnapshotStore snapshots = new RecordingSnapshotStore();
        ServerRegistry registry = new ServerRegistry(new IdAllocator(false));
        registry.setSnapshotStore(snapshots);
        RegistrationRequest request = registration("temp-mini-attested");
        request.setDataAuthorityAttestation(attestation("registration-fingerprint"));
        request.setAuthorityDeliveryManifest(authorityDeliveryManifest("registration-manifest"));

        String serverId = registry.registerServer(request);

        assertThat(registry.getServer(serverId).getDataAuthorityAttestation().getAttestationFingerprint())
            .isEqualTo("registration-fingerprint");
        assertThat(registry.getServer(serverId).getAuthorityDeliveryManifest().getManifestFingerprint())
            .isEqualTo("registration-manifest");
        assertThat(snapshots.serverAttestationFingerprints).containsExactly("registration-fingerprint");
        assertThat(snapshots.serverAuthorityManifestFingerprints).containsExactly("registration-manifest");
    }

    @Test
    void serverRegistryUpdatesAuthorityDeliveryManifestFromHeartbeat() {
        RecordingSnapshotStore snapshots = new RecordingSnapshotStore();
        ServerRegistry registry = new ServerRegistry(new IdAllocator(false));
        registry.setSnapshotStore(snapshots);
        String serverId = registry.registerServer(registration("temp-mini-manifest"));

        registry.updateAuthorityDeliveryManifest(serverId, authorityDeliveryManifest("heartbeat-manifest"));

        assertThat(registry.getServer(serverId).getAuthorityDeliveryManifest().getManifestFingerprint())
            .isEqualTo("heartbeat-manifest");
        assertThat(snapshots.serverAuthorityManifestFingerprints).containsExactly("heartbeat-manifest");
    }

    @Test
    void proxyRegistryEmitsSnapshotsForLifecycleChanges() {
        RecordingSnapshotStore snapshots = new RecordingSnapshotStore();
        ProxyRegistry registry = new ProxyRegistry(new IdAllocator(false), false);
        registry.setSnapshotStore(snapshots);

        try {
            String proxyId = registry.registerProxy("temp-proxy-1", "127.0.0.1", 25577);
            registry.updateHeartbeat(proxyId);
            registry.deregisterProxy(proxyId);

            assertThat(snapshots.proxyStates).contains(
                RegisteredProxyData.Status.AVAILABLE.name(),
                RegisteredProxyData.Status.UNAVAILABLE.name()
            );
        } finally {
            registry.shutdown();
        }
    }

    @Test
    void serverRegistryRestoresSnapshotsAsUnavailableAndSeedsIds() {
        IdAllocator allocator = new IdAllocator(false);
        ServerRegistry registry = new ServerRegistry(allocator);
        RuntimeAuthorityDeliveryManifest deliveryManifest = currentAuthorityDeliveryManifest();
        RegistryNodeSnapshot snapshot = new RegistryNodeSnapshot(
            "mini7",
            "BACKEND",
            "127.0.0.1",
            25565,
            "game",
            RegisteredServerData.Status.RUNNING.name(),
            40,
            Map.of(
                "tempId", "temp-mini-7",
                "serverType", "mini",
                "playerCount", 12,
                "tps", 19.8,
                "dataAuthorityAttestation", attestationMetadata("restore-fingerprint"),
                "authorityDeliveryManifest", authorityDeliveryManifestMetadata(deliveryManifest),
                "slotFamilies", Map.of("duels", 2),
                "slots", List.of(Map.of(
                    "slotId", "mini7A",
                    "slotSuffix", "A",
                    "gameType", "duels",
                    "status", SlotLifecycleStatus.AVAILABLE.name(),
                    "maxPlayers", 8,
                    "onlinePlayers", 3,
                    "metadata", Map.of("family", "duels")
                ))
            ),
            Instant.now().minusSeconds(60),
            Instant.now().minusSeconds(30)
        ).withAttestation(UUID.randomUUID(), "registry-test");

        assertThat(snapshot.hasValidAttestation()).isTrue();
        assertThat(registry.restoreSnapshots(List.of(snapshot))).isEqualTo(1);

        RegisteredServerData restored = registry.getServer("mini7");
        assertThat(restored).isNotNull();
        assertThat(restored.getStatus()).isEqualTo(RegisteredServerData.Status.UNAVAILABLE);
        assertThat(restored.getPlayerCount()).isEqualTo(12);
        assertThat(restored.getDataAuthorityAttestation().getAttestationFingerprint())
            .isEqualTo("restore-fingerprint");
        assertThat(restored.getAuthorityDeliveryManifest().getManifestFingerprint())
            .isEqualTo(deliveryManifest.getManifestFingerprint());
        assertThat(restored.getSlotFamilyCapacities()).containsEntry("duels", 2);
        assertThat(restored.getSlot("A").getStatus()).isEqualTo(SlotLifecycleStatus.PROVISIONING);
        assertThat(restored.getSlot("A").getMetadata())
            .containsEntry("family", "duels")
            .containsEntry("restoredStatus", SlotLifecycleStatus.AVAILABLE.name())
            .containsEntry("restoreRequiresFreshSlotStatus", "true");

        SlotStatusUpdateMessage freshSlotStatus = new SlotStatusUpdateMessage("mini7", "mini7A");
        freshSlotStatus.setSlotSuffix("A");
        freshSlotStatus.setStatus(SlotLifecycleStatus.AVAILABLE);
        freshSlotStatus.setMaxPlayers(8);
        freshSlotStatus.setOnlinePlayers(0);
        freshSlotStatus.setMetadata(Map.of("family", "duels"));
        registry.updateSlot("mini7", freshSlotStatus);
        assertThat(restored.getSlot("A").getStatus()).isEqualTo(SlotLifecycleStatus.AVAILABLE);
        assertThat(restored.getSlot("A").getMetadata())
            .containsEntry("family", "duels")
            .doesNotContainKeys("restoredStatus", "restoreRequiresFreshSlotStatus");

        String nextServerId = registry.registerServer(registration("temp-mini-next"));
        assertThat(nextServerId).isEqualTo("mini8");
    }

    @Test
    void serverRegistryDropsStaleAuthorityManifestFromRestoredSnapshot() {
        ServerRegistry registry = new ServerRegistry(new IdAllocator(false));
        RegistryNodeSnapshot snapshot = new RegistryNodeSnapshot(
            "mini7",
            "BACKEND",
            "127.0.0.1",
            25565,
            "game",
            RegisteredServerData.Status.RUNNING.name(),
            40,
            Map.of(
                "tempId", "temp-mini-7",
                "serverType", "mini",
                "authorityDeliveryManifest", authorityDeliveryManifestMetadata(authorityDeliveryManifest("stale"))
            ),
            Instant.now().minusSeconds(60),
            Instant.now().minusSeconds(30)
        ).withAttestation(UUID.randomUUID(), "registry-test");

        assertThat(registry.restoreSnapshots(List.of(snapshot))).isEqualTo(1);
        assertThat(registry.getServer("mini7").getAuthorityDeliveryManifest()).isNull();
    }

    @Test
    void serverRegistryRefusesTamperedAttestedSnapshotsWithoutSeedingIds() {
        IdAllocator allocator = new IdAllocator(false);
        ServerRegistry registry = new ServerRegistry(allocator);
        RegistryNodeSnapshot original = new RegistryNodeSnapshot(
            "mini7",
            "BACKEND",
            "127.0.0.1",
            25565,
            "game",
            RegisteredServerData.Status.RUNNING.name(),
            40,
            Map.of("tempId", "temp-mini-7", "serverType", "mini"),
            Instant.now().minusSeconds(60),
            Instant.now().minusSeconds(30)
        ).withAttestation(UUID.randomUUID(), "registry-test");
        RegistryNodeSnapshot tampered = new RegistryNodeSnapshot(
            original.nodeId(),
            original.nodeType(),
            original.address(),
            original.port(),
            original.role(),
            original.state(),
            120,
            original.metadata(),
            original.registeredAt(),
            original.updatedAt(),
            original.snapshotVersion(),
            original.snapshotId(),
            original.snapshotSource(),
            original.snapshotFingerprint()
        );

        assertThat(tampered.hasAttestation()).isTrue();
        assertThat(tampered.hasValidAttestation()).isFalse();
        assertThat(registry.restoreSnapshots(List.of(tampered))).isZero();
        assertThat(registry.getServer("mini7")).isNull();
        assertThat(registry.registerServer(registration("temp-mini-next"))).isEqualTo("mini1");
    }

    @Test
    void serverRegistryUsesTerminalSnapshotsOnlyToSeedIds() {
        IdAllocator allocator = new IdAllocator(false);
        ServerRegistry registry = new ServerRegistry(allocator);
        RegistryNodeSnapshot snapshot = new RegistryNodeSnapshot(
            "mini3",
            "BACKEND",
            "127.0.0.1",
            25565,
            "game",
            RegisteredServerData.Status.DEAD.name(),
            20,
            Map.of("serverType", "mini"),
            Instant.now().minusSeconds(120),
            Instant.now().minusSeconds(60)
        ).withAttestation(UUID.randomUUID(), "registry-test");

        assertThat(registry.restoreSnapshots(List.of(snapshot))).isZero();
        assertThat(registry.getServer("mini3")).isNull();
        assertThat(registry.registerServer(registration("temp-mini-next"))).isEqualTo("mini4");
    }

    @Test
    void proxyRegistryRestoresSnapshotsAsUnavailableReservations() {
        ProxyRegistry registry = new ProxyRegistry(new IdAllocator(false), false);
        String proxyId = "proxy-00000000-0000-0000-0000-000000000001-1-1700000000000";
        RegistryNodeSnapshot snapshot = new RegistryNodeSnapshot(
            proxyId,
            "PROXY",
            "127.0.0.1",
            25577,
            "proxy",
            RegisteredProxyData.Status.AVAILABLE.name(),
            0,
            Map.of(),
            Instant.now().minusSeconds(60),
            Instant.now().minusSeconds(30)
        ).withAttestation(UUID.randomUUID(), "registry-test");

        try {
            assertThat(snapshot.hasValidAttestation()).isTrue();
            assertThat(registry.restoreSnapshots(List.of(snapshot))).isEqualTo(1);
            assertThat(registry.getProxyCount()).isZero();
            assertThat(registry.getUnavailableProxyCount()).isEqualTo(1);

            registry.registerProxy(ProxyIdentifier.parse(proxyId), "127.0.0.1", 25577);

            assertThat(registry.getProxyCount()).isEqualTo(1);
            assertThat(registry.getUnavailableProxyCount()).isZero();
        } finally {
            registry.shutdown();
        }
    }

    @Test
    void proxyRegistryRefusesTamperedAttestedSnapshots() {
        ProxyRegistry registry = new ProxyRegistry(new IdAllocator(false), false);
        String proxyId = "proxy-00000000-0000-0000-0000-000000000001-1-1700000000000";
        RegistryNodeSnapshot original = new RegistryNodeSnapshot(
            proxyId,
            "PROXY",
            "127.0.0.1",
            25577,
            "proxy",
            RegisteredProxyData.Status.AVAILABLE.name(),
            0,
            Map.of(),
            Instant.now().minusSeconds(60),
            Instant.now().minusSeconds(30)
        ).withAttestation(UUID.randomUUID(), "registry-test");
        RegistryNodeSnapshot tampered = new RegistryNodeSnapshot(
            original.nodeId(),
            original.nodeType(),
            "127.0.0.2",
            original.port(),
            original.role(),
            original.state(),
            original.capacity(),
            original.metadata(),
            original.registeredAt(),
            original.updatedAt(),
            original.snapshotVersion(),
            original.snapshotId(),
            original.snapshotSource(),
            original.snapshotFingerprint()
        );

        try {
            assertThat(tampered.hasAttestation()).isTrue();
            assertThat(tampered.hasValidAttestation()).isFalse();
            assertThat(registry.restoreSnapshots(List.of(tampered))).isZero();
            assertThat(registry.getProxyCount()).isZero();
            assertThat(registry.getUnavailableProxyCount()).isZero();
        } finally {
            registry.shutdown();
        }
    }

    private static RegistrationRequest registration(String tempId) {
        RegistrationRequest request = new RegistrationRequest();
        request.setTempId(tempId);
        request.setServerType("mini");
        request.setAddress("127.0.0.1");
        request.setPort(25565);
        request.setMaxCapacity(20);
        return request;
    }

    private static RuntimeDataAuthorityAttestation attestation(String fingerprint) {
        return new RuntimeDataAuthorityAttestation(
            "Paper",
            1,
            true,
            "remote-authority",
            "watermarked-snapshot-cache",
            1,
            "command-contract",
            1,
            "read-contract",
            "config-fingerprint",
            "classpath-fingerprint",
            fingerprint
        );
    }

    private static RuntimeAuthorityDeliveryManifest authorityDeliveryManifest(String fingerprint) {
        return new RuntimeAuthorityDeliveryManifest(
            "Paper",
            1,
            "registry-service",
            "remote-authority",
            "watermarked-snapshot-cache",
            "attestation-fingerprint",
            1,
            "command-contract",
            "route-manifest",
            1,
            "read-contract",
            Map.of("GRANT_RANK", "rank"),
            Map.of("GRANT_RANK", "SYNC_INTERACTIVE"),
            Map.of("GRANT_RANK", "rank:player:{aggregateId}=>rank:player:{aggregateId}"),
            Map.of("GRANT_RANK", "kafka"),
            Map.of("GRANT_RANK", "cassandra"),
            Map.of("GRANT_RANK", "postgresql"),
            Map.of("GRANT_RANK", "valkey"),
            Map.of("PLAYER_RANK", "player_rank"),
            Map.of("PLAYER_RANK", "cassandra"),
            Map.of("PLAYER_RANK", "valkey"),
            fingerprint
        );
    }

    private static RuntimeAuthorityDeliveryManifest currentAuthorityDeliveryManifest() {
        return RuntimeAuthorityDeliveryManifest.create(
            "Paper",
            1,
            "registry-service",
            "remote-authority",
            "watermarked-snapshot-cache",
            "attestation-fingerprint",
            DataAuthority.COMMAND_SCHEMA_VERSION,
            DataAuthorityCommandContracts.fingerprint(),
            DataAuthorityCommandContracts.routeManifestFingerprint(),
            AuthorityDomainTopology.fingerprint(),
            authorityServicesByDomain(),
            authorityConsumerGroupsByDomain(),
            authorityPrincipalsByDomain(),
            DataAuthorityReadContracts.schemaVersion(),
            DataAuthorityReadContracts.fingerprint(),
            commandDomainsByType(),
            commandDeliveryModesByType(),
            DataAuthorityCommandContracts.routePartitionKeyVectors(),
            commandAuthorityServicesByType(),
            commandConsumerGroupsByType(),
            commandAuthorityPrincipalsByType(),
            commandPartitionCountsByType(),
            DataAuthorityCommandContracts.commandTopicsByDeclarationId(),
            DataAuthorityCommandContracts.responseTopicsByDeclarationId(),
            DataAuthorityCommandContracts.eventTopicsByDeclarationId(),
            DataAuthorityCommandContracts.stateTopicsByDeclarationId(),
            commandLogStoresByType(),
            commandHotProjectionStoresByType(),
            commandHistoryStoresByType(),
            commandCacheStoresByType(),
            readProjectionFamiliesByType(),
            readServingStoresByType(),
            readCacheStoresByType()
        );
    }

    private static Map<String, Object> attestationMetadata(String fingerprint) {
        return Map.ofEntries(
            Map.entry("nodeKind", "Paper"),
            Map.entry("manifestVersion", 1),
            Map.entry("passed", true),
            Map.entry("runtimeDataMode", "remote-authority"),
            Map.entry("cacheMode", "watermarked-snapshot-cache"),
            Map.entry("commandSchemaVersion", 1),
            Map.entry("commandContractFingerprint", "command-contract"),
            Map.entry("readSchemaVersion", 1),
            Map.entry("readContractFingerprint", "read-contract"),
            Map.entry("configFingerprint", "config-fingerprint"),
            Map.entry("classpathFingerprint", "classpath-fingerprint"),
            Map.entry("attestationFingerprint", fingerprint)
        );
    }

    private static Map<String, Object> authorityDeliveryManifestMetadata(RuntimeAuthorityDeliveryManifest manifest) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("nodeKind", manifest.getNodeKind());
        metadata.put("manifestVersion", manifest.getManifestVersion());
        metadata.put("authorityServerId", manifest.getAuthorityServerId());
        metadata.put("runtimeDataMode", manifest.getRuntimeDataMode());
        metadata.put("cacheMode", manifest.getCacheMode());
        metadata.put("startupAttestationFingerprint", manifest.getStartupAttestationFingerprint());
        metadata.put("commandSchemaVersion", manifest.getCommandSchemaVersion());
        metadata.put("commandContractFingerprint", manifest.getCommandContractFingerprint());
        metadata.put("commandRouteManifestFingerprint", manifest.getCommandRouteManifestFingerprint());
        metadata.put("authorityDomainTopologyFingerprint", manifest.getAuthorityDomainTopologyFingerprint());
        metadata.put("authorityServicesByDomain", manifest.getAuthorityServicesByDomain());
        metadata.put("authorityConsumerGroupsByDomain", manifest.getAuthorityConsumerGroupsByDomain());
        metadata.put("authorityPrincipalsByDomain", manifest.getAuthorityPrincipalsByDomain());
        metadata.put("readSchemaVersion", manifest.getReadSchemaVersion());
        metadata.put("readContractFingerprint", manifest.getReadContractFingerprint());
        metadata.put("commandDomainsByType", manifest.getCommandDomainsByType());
        metadata.put("commandDeliveryModesByType", manifest.getCommandDeliveryModesByType());
        metadata.put("commandPartitionKeyVectorsByType", manifest.getCommandPartitionKeyVectorsByType());
        metadata.put("commandAuthorityServicesByType", manifest.getCommandAuthorityServicesByType());
        metadata.put("commandConsumerGroupsByType", manifest.getCommandConsumerGroupsByType());
        metadata.put("commandAuthorityPrincipalsByType", manifest.getCommandAuthorityPrincipalsByType());
        metadata.put("commandPartitionCountsByType", manifest.getCommandPartitionCountsByType());
        metadata.put("commandTopicsByType", manifest.getCommandTopicsByType());
        metadata.put("commandResponseTopicsByType", manifest.getCommandResponseTopicsByType());
        metadata.put("commandEventTopicsByType", manifest.getCommandEventTopicsByType());
        metadata.put("commandStateTopicsByType", manifest.getCommandStateTopicsByType());
        metadata.put("commandLogStoresByType", manifest.getCommandLogStoresByType());
        metadata.put("commandHotProjectionStoresByType", manifest.getCommandHotProjectionStoresByType());
        metadata.put("commandHistoryStoresByType", manifest.getCommandHistoryStoresByType());
        metadata.put("commandCacheStoresByType", manifest.getCommandCacheStoresByType());
        metadata.put("readProjectionFamiliesByType", manifest.getReadProjectionFamiliesByType());
        metadata.put("readServingStoresByType", manifest.getReadServingStoresByType());
        metadata.put("readCacheStoresByType", manifest.getReadCacheStoresByType());
        metadata.put("manifestFingerprint", manifest.getManifestFingerprint());
        return metadata;
    }

    private static Map<String, String> commandDomainsByType() {
        return commandMetadataByType(DataAuthorityCommandContracts.CommandContract::domain);
    }

    private static Map<String, String> commandDeliveryModesByType() {
        return commandMetadataByType(contract -> contract.deliveryMode().name());
    }

    private static Map<String, String> authorityServicesByDomain() {
        return domainTopologyMetadata(AuthorityDomainTopology.DomainTopology::authorityService);
    }

    private static Map<String, String> authorityConsumerGroupsByDomain() {
        return domainTopologyMetadata(AuthorityDomainTopology.DomainTopology::consumerGroup);
    }

    private static Map<String, String> authorityPrincipalsByDomain() {
        return domainTopologyMetadata(AuthorityDomainTopology.DomainTopology::authorityPrincipal);
    }

    private static Map<String, String> commandAuthorityServicesByType() {
        return commandTopologyMetadataByType(AuthorityDomainTopology.DomainTopology::authorityService);
    }

    private static Map<String, String> commandConsumerGroupsByType() {
        return commandTopologyMetadataByType(AuthorityDomainTopology.DomainTopology::consumerGroup);
    }

    private static Map<String, String> commandAuthorityPrincipalsByType() {
        return commandTopologyMetadataByType(AuthorityDomainTopology.DomainTopology::authorityPrincipal);
    }

    private static Map<String, String> commandPartitionCountsByType() {
        return commandTopologyMetadataByType(topology -> Integer.toString(topology.partitionCount()));
    }

    private static Map<String, String> commandLogStoresByType() {
        return commandMetadataByType(DataAuthorityCommandContracts.CommandContract::commandLogStore);
    }

    private static Map<String, String> commandHotProjectionStoresByType() {
        return commandMetadataByType(DataAuthorityCommandContracts.CommandContract::hotProjectionStore);
    }

    private static Map<String, String> commandHistoryStoresByType() {
        return commandMetadataByType(DataAuthorityCommandContracts.CommandContract::historyStore);
    }

    private static Map<String, String> commandCacheStoresByType() {
        return commandMetadataByType(DataAuthorityCommandContracts.CommandContract::cacheStore);
    }

    private static Map<String, String> readProjectionFamiliesByType() {
        return readMetadataByType(DataAuthorityReadContracts.ReadContract::projectionFamily);
    }

    private static Map<String, String> readServingStoresByType() {
        return readMetadataByType(DataAuthorityReadContracts.ReadContract::servingStore);
    }

    private static Map<String, String> readCacheStoresByType() {
        return readMetadataByType(DataAuthorityReadContracts.ReadContract::cacheStore);
    }

    private static Map<String, String> commandMetadataByType(
        Function<DataAuthorityCommandContracts.CommandContract, String> extractor
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        DataAuthorityCommandContracts.allByDeclarationId().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> values.put(entry.getKey(), extractor.apply(entry.getValue())));
        return Map.copyOf(values);
    }

    private static Map<String, String> commandTopologyMetadataByType(
        Function<AuthorityDomainTopology.DomainTopology, String> extractor
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        DataAuthorityCommandContracts.allByDeclarationId().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> values.put(
                entry.getKey(),
                extractor.apply(AuthorityDomainTopology.domain(entry.getValue().domain()))
            ));
        return Map.copyOf(values);
    }

    private static Map<String, String> domainTopologyMetadata(
        Function<AuthorityDomainTopology.DomainTopology, String> extractor
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        AuthorityDomainTopology.all().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> values.put(entry.getKey(), extractor.apply(entry.getValue())));
        return Map.copyOf(values);
    }

    private static Map<String, String> readMetadataByType(
        Function<DataAuthorityReadContracts.ReadContract, String> extractor
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        DataAuthorityReadContracts.all().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> values.put(entry.getKey().name(), extractor.apply(entry.getValue())));
        return Map.copyOf(values);
    }

    private static Set<String> registrySnapshotColumns() {
        return new LinkedHashSet<>(List.of(
            "node_id",
            "node_type",
            "address",
            "port",
            "role",
            "state",
            "capacity",
            "metadata",
            "registered_at",
            "updated_at",
            "snapshot_id",
            "snapshot_source",
            "snapshot_version",
            "snapshot_fingerprint"
        ));
    }

    private static final class RecordingSnapshotStore implements RegistryNodeSnapshotStore {
        private final List<String> serverStates = new ArrayList<>();
        private final List<String> serverAttestationFingerprints = new ArrayList<>();
        private final List<String> serverAuthorityManifestFingerprints = new ArrayList<>();
        private final List<String> proxyStates = new ArrayList<>();
        private final List<String> offlineNodes = new ArrayList<>();

        @Override
        public void snapshotServer(RegisteredServerData server) {
            serverStates.add(server.getStatus().name());
            RuntimeDataAuthorityAttestation attestation = server.getDataAuthorityAttestation();
            if (attestation != null) {
                serverAttestationFingerprints.add(attestation.getAttestationFingerprint());
            }
            RuntimeAuthorityDeliveryManifest manifest = server.getAuthorityDeliveryManifest();
            if (manifest != null) {
                serverAuthorityManifestFingerprints.add(manifest.getManifestFingerprint());
            }
        }

        @Override
        public void snapshotProxy(RegisteredProxyData proxy) {
            proxyStates.add(proxy.getStatus().name());
        }

        @Override
        public void markOffline(String nodeId, String nodeType, String state) {
            offlineNodes.add(nodeId + ":" + nodeType + ":" + state);
        }
    }
}
