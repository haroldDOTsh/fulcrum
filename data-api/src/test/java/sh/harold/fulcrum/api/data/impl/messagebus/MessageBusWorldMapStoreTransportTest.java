package sh.harold.fulcrum.api.data.impl.messagebus;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.world.WorldMapStore;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusAdapter;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusConnectionConfig;
import sh.harold.fulcrum.api.messagebus.impl.InMemoryMessageBus;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageBusWorldMapStoreTransportTest {
    @Test
    void clientLoadsWorldMapsThroughProvider() {
        UUID id = UUID.randomUUID();
        Instant updatedAt = Instant.ofEpochMilli(123456789L);
        byte[] schematicBytes = new byte[] {1, 2, 3, 4};
        WorldMapStore backend = new FixedWorldMapStore(List.of(new WorldMapStore.WorldMapRecord(
            id,
            "arena",
            "Arena",
            "{\"mapId\":\"arena\"}",
            schematicBytes,
            updatedAt
        )));

        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        MessageBusWorldMapStoreProvider provider = new MessageBusWorldMapStoreProvider(bus, backend);
        provider.start();

        MessageBusWorldMapStoreClient client = new MessageBusWorldMapStoreClient(
            bus,
            "authority-1",
            Duration.ofSeconds(1)
        );
        WorldMapStore.WorldMapCatalogSnapshot catalog = client.loadWorldCatalog()
            .toCompletableFuture()
            .join();
        List<WorldMapStore.WorldMapRecord> records = catalog.records();

        assertThat(records).hasSize(1);
        WorldMapStore.WorldMapRecord record = records.get(0);
        assertThat(record.id()).isEqualTo(id);
        assertThat(record.worldName()).isEqualTo("arena");
        assertThat(record.displayName()).isEqualTo("Arena");
        assertThat(record.metadataJson()).isEqualTo("{\"mapId\":\"arena\"}");
        assertThat(record.schematicBytes()).containsExactly(schematicBytes);
        assertThat(record.updatedAt()).isEqualTo(updatedAt);
        assertThat(catalog.receipt().operation()).isEqualTo("LOAD");
        assertThat(catalog.receipt().aggregateScope()).isEqualTo(WorldMapStore.CATALOG_SCOPE);
        assertThat(catalog.receipt().authorityNodeId()).isEqualTo("authority-1");
        assertThat(catalog.receipt().authorityRoute()).isEqualTo("messagebus:authority-1->authority-1");
        assertThat(catalog.receipt().sourceTier()).isEqualTo("authority-world-map");
        assertThat(catalog.receipt().schemaVersion()).isEqualTo(MessageBusWorldMapContracts.schemaVersion());
        assertThat(catalog.receipt().contractFingerprint()).isEqualTo(MessageBusWorldMapContracts.fingerprint());
        assertThat(catalog.receipt().resultFingerprint())
            .isEqualTo(WorldMapStore.WorldMapAuthorityReceipt.catalogFingerprint(records));
    }

    @Test
    void clientSavesWorldMapThroughProvider() {
        UUID id = UUID.randomUUID();
        Instant updatedAt = Instant.ofEpochMilli(987654321L);
        AtomicReference<SaveCall> received = new AtomicReference<>();
        WorldMapStore backend = new CapturingWorldMapStore(received, new WorldMapStore.MapSaveResult(id, updatedAt));

        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        MessageBusWorldMapStoreProvider provider = new MessageBusWorldMapStoreProvider(bus, backend);
        provider.start();

        MessageBusWorldMapStoreClient client = new MessageBusWorldMapStoreClient(bus, "authority-1");
        WorldMapStore.ReceiptedMapSaveResult saved = client.saveWorldDefinitionWithReceipt(
            "game-1",
            "arena",
            "Arena",
            "{\"mapId\":\"arena\"}",
            new byte[] {9, 8, 7}
        ).toCompletableFuture().join();
        WorldMapStore.MapSaveResult result = saved.result();

        assertThat(result.id()).isEqualTo(id);
        assertThat(result.updatedAt()).isEqualTo(updatedAt);
        assertThat(saved.receipt().operation()).isEqualTo("SAVE");
        assertThat(saved.receipt().aggregateScope()).isEqualTo("world-map:arena");
        assertThat(saved.receipt().authorityNodeId()).isEqualTo("authority-1");
        assertThat(saved.receipt().authorityRoute()).isEqualTo("messagebus:authority-1->authority-1");
        assertThat(saved.receipt().sourceTier()).isEqualTo("authority-world-map");
        assertThat(saved.receipt().schemaVersion()).isEqualTo(MessageBusWorldMapContracts.schemaVersion());
        assertThat(saved.receipt().contractFingerprint()).isEqualTo(MessageBusWorldMapContracts.fingerprint());
        assertThat(saved.receipt().resultFingerprint())
            .isEqualTo(WorldMapStore.WorldMapAuthorityReceipt.saveFingerprint("world-map:arena", result));
        assertThat(received.get()).isNotNull();
        assertThat(received.get().serverId()).isEqualTo("game-1");
        assertThat(received.get().worldName()).isEqualTo("arena");
        assertThat(received.get().displayName()).isEqualTo("Arena");
        assertThat(received.get().metadataJson()).isEqualTo("{\"mapId\":\"arena\"}");
        assertThat(received.get().schematicBytes()).containsExactly(9, 8, 7);
    }

    @Test
    void providerRejectsContractFingerprintMismatchWithoutDelegating() {
        AtomicBoolean delegated = new AtomicBoolean();
        WorldMapStore backend = new RejectingWorldMapStore(delegated);

        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        MessageBusWorldMapStoreProvider provider = new MessageBusWorldMapStoreProvider(bus, backend);
        provider.start();

        Map<String, Object> wire = new LinkedHashMap<>(MessageBusWorldMapContracts.payload(
            MessageBusWorldMapContracts.Operation.SAVE,
            Map.of(
                "serverId", "game-1",
                "worldName", "arena",
                "metadataJson", "{}",
                "schematicBase64", "AQID"
            )
        ));
        wire.put("contractFingerprint", "0000000000000000000000000000000000000000000000000000000000000000");

        Object rawResponse = bus.request(
            "authority-1",
            MessageBusWorldMapChannels.SAVE,
            wire,
            Duration.ofSeconds(1)
        ).join();

        assertThat(delegated).isFalse();
        assertThat(rawResponse).isInstanceOf(Map.class);
        Map<?, ?> response = (Map<?, ?>) rawResponse;
        assertThat(response.get("rejectionReason")).isEqualTo("VALIDATION_FAILED");
        assertThat(response.get("message").toString()).contains("World map transport contract fingerprint mismatch");
    }

    @Test
    void clientRejectsLoadResponseWithoutAuthorityReceipt() {
        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        bus.subscribeRequest(
            MessageBusWorldMapChannels.LOAD,
            envelope -> CompletableFuture.completedFuture(Map.of("worlds", List.of()))
        );

        MessageBusWorldMapStoreClient client = new MessageBusWorldMapStoreClient(bus, "authority-1");

        assertThatThrownBy(() -> client.loadWorlds().toCompletableFuture().join())
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage("World map LOAD response receipt is required");
    }

    @Test
    void clientRejectsSaveResponseWithoutAuthorityReceipt() {
        UUID id = UUID.randomUUID();
        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        bus.subscribeRequest(
            MessageBusWorldMapChannels.SAVE,
            envelope -> CompletableFuture.completedFuture(Map.of(
                "id", id.toString(),
                "updatedAtEpochMillis", 987654321L
            ))
        );

        MessageBusWorldMapStoreClient client = new MessageBusWorldMapStoreClient(bus, "authority-1");

        assertThatThrownBy(() -> client.saveWorldDefinition(
                "game-1",
                "arena",
                "Arena",
                "{\"mapId\":\"arena\"}",
                new byte[] {9, 8, 7}
            ).toCompletableFuture().join())
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage("World map SAVE response receipt is required");
    }

    private record SaveCall(
        String serverId,
        String worldName,
        String displayName,
        String metadataJson,
        byte[] schematicBytes
    ) {}

    private record FixedWorldMapStore(List<WorldMapStore.WorldMapRecord> records) implements WorldMapStore {
        @Override
        public CompletionStage<List<WorldMapRecord>> loadWorlds() {
            return CompletableFuture.completedFuture(records);
        }

        @Override
        public CompletionStage<MapSaveResult> saveWorldDefinition(
            String serverId,
            String worldName,
            String displayName,
            String metadataJson,
            byte[] schematicBytes
        ) {
            throw new UnsupportedOperationException("save not used");
        }
    }

    private record RejectingWorldMapStore(AtomicBoolean delegated) implements WorldMapStore {
        @Override
        public CompletionStage<List<WorldMapRecord>> loadWorlds() {
            delegated.set(true);
            throw new AssertionError("World map backend should not be called");
        }

        @Override
        public CompletionStage<MapSaveResult> saveWorldDefinition(
            String serverId,
            String worldName,
            String displayName,
            String metadataJson,
            byte[] schematicBytes
        ) {
            delegated.set(true);
            throw new AssertionError("World map backend should not be called");
        }
    }

    private record CapturingWorldMapStore(
        AtomicReference<SaveCall> received,
        WorldMapStore.MapSaveResult result
    ) implements WorldMapStore {
        @Override
        public CompletionStage<List<WorldMapRecord>> loadWorlds() {
            throw new UnsupportedOperationException("load not used");
        }

        @Override
        public CompletionStage<MapSaveResult> saveWorldDefinition(
            String serverId,
            String worldName,
            String displayName,
            String metadataJson,
            byte[] schematicBytes
        ) {
            received.set(new SaveCall(serverId, worldName, displayName, metadataJson, schematicBytes));
            return CompletableFuture.completedFuture(result);
        }
    }

    private record TestAdapter(String serverId) implements MessageBusAdapter {
        @Override
        public String getServerId() {
            return serverId;
        }

        @Override
        public Executor getAsyncExecutor() {
            return Runnable::run;
        }

        @Override
        public Logger getLogger() {
            return Logger.getLogger(MessageBusWorldMapStoreTransportTest.class.getName());
        }

        @Override
        public MessageBusConnectionConfig getConnectionConfig() {
            return MessageBusConnectionConfig.builder()
                .type(MessageBusConnectionConfig.MessageBusType.IN_MEMORY)
                .build();
        }

        @Override
        public boolean isRunning() {
            return true;
        }
    }
}
