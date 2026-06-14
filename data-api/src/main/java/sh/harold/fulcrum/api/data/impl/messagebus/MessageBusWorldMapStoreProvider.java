package sh.harold.fulcrum.api.data.impl.messagebus;

import com.google.gson.Gson;
import sh.harold.fulcrum.api.data.world.WorldMapStore;
import sh.harold.fulcrum.api.data.world.WorldMapStore.MapSaveResult;
import sh.harold.fulcrum.api.data.world.WorldMapStore.WorldMapAuthorityReceipt;
import sh.harold.fulcrum.api.data.world.WorldMapStore.WorldMapRecord;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.RequestHandler;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class MessageBusWorldMapStoreProvider implements AutoCloseable {
    private final MessageBus messageBus;
    private final WorldMapStore worldMapStore;
    private final Gson gson = new Gson();

    private final RequestHandler loadHandler = this::handleLoad;
    private final RequestHandler saveHandler = this::handleSave;

    public MessageBusWorldMapStoreProvider(MessageBus messageBus, WorldMapStore worldMapStore) {
        this.messageBus = Objects.requireNonNull(messageBus, "messageBus");
        this.worldMapStore = Objects.requireNonNull(worldMapStore, "worldMapStore");
    }

    public void start() {
        messageBus.subscribeRequest(MessageBusWorldMapChannels.LOAD, loadHandler);
        messageBus.subscribeRequest(MessageBusWorldMapChannels.SAVE, saveHandler);
    }

    @Override
    public void close() {
        messageBus.unsubscribeRequest(MessageBusWorldMapChannels.LOAD, loadHandler);
        messageBus.unsubscribeRequest(MessageBusWorldMapChannels.SAVE, saveHandler);
    }

    private CompletionStage<Object> handleLoad(MessageEnvelope envelope) {
        Map<String, Object> payload = mapPayload(envelope);
        String rejection = MessageBusWorldMapContracts.rejection(
            MessageBusWorldMapContracts.Operation.LOAD,
            payload
        );
        if (rejection != null) {
            return CompletableFuture.completedFuture(rejectionResponse(
                MessageBusWorldMapContracts.Operation.LOAD,
                rejection
            ));
        }
        return worldMapStore.loadWorlds().thenApply(records -> loadResponse(envelope, records));
    }

    private CompletionStage<Object> handleSave(MessageEnvelope envelope) {
        Map<String, Object> payload = mapPayload(envelope);
        String rejection = MessageBusWorldMapContracts.rejection(
            MessageBusWorldMapContracts.Operation.SAVE,
            payload
        );
        if (rejection != null) {
            return CompletableFuture.completedFuture(rejectionResponse(
                MessageBusWorldMapContracts.Operation.SAVE,
                rejection
            ));
        }
        return worldMapStore.saveWorldDefinition(
            string(payload.get("serverId")),
            string(payload.get("worldName")),
            string(payload.get("displayName")),
            string(payload.get("metadataJson")),
            base64(payload.get("schematicBase64"))
        ).thenApply(result -> saveResponse(envelope, payload, result));
    }

    private Map<String, Object> loadResponse(MessageEnvelope envelope, List<WorldMapRecord> records) {
        List<Map<String, Object>> worlds = new ArrayList<>();
        List<WorldMapRecord> safeRecords = records == null ? List.of() : List.copyOf(records);
        for (WorldMapRecord record : safeRecords) {
            worlds.add(new MapBuilder()
                .put("id", record.id().toString())
                .put("worldName", record.worldName())
                .put("displayName", record.displayName())
                .put("metadataJson", record.metadataJson())
                .put("schematicBase64", Base64.getEncoder().encodeToString(record.schematicBytes()))
                .put("updatedAtEpochMillis", record.updatedAt().toEpochMilli())
                .build());
        }
        WorldMapAuthorityReceipt receipt = receipt(
            envelope,
            MessageBusWorldMapContracts.Operation.LOAD,
            WorldMapStore.CATALOG_SCOPE,
            WorldMapAuthorityReceipt.requestFingerprint("LOAD", WorldMapStore.CATALOG_SCOPE),
            WorldMapAuthorityReceipt.catalogFingerprint(safeRecords)
        );
        return new MapBuilder()
            .put("worlds", List.copyOf(worlds))
            .put("receipt", receipt.payload())
            .build();
    }

    private Map<String, Object> saveResponse(
        MessageEnvelope envelope,
        Map<String, Object> payload,
        MapSaveResult result
    ) {
        String aggregateScope = WorldMapAuthorityReceipt.worldScope(string(payload.get("worldName")));
        WorldMapAuthorityReceipt receipt = receipt(
            envelope,
            MessageBusWorldMapContracts.Operation.SAVE,
            aggregateScope,
            WorldMapAuthorityReceipt.requestFingerprint("SAVE", aggregateScope),
            WorldMapAuthorityReceipt.saveFingerprint(aggregateScope, result)
        );
        return new MapBuilder()
            .put("id", result.id().toString())
            .put("updatedAtEpochMillis", result.updatedAt().toEpochMilli())
            .put("receipt", receipt.payload())
            .build();
    }

    private Map<String, Object> rejectionResponse(
        MessageBusWorldMapContracts.Operation operation,
        String message
    ) {
        return new MapBuilder()
            .put("operation", operation.name())
            .put("schemaVersion", MessageBusWorldMapContracts.schemaVersion())
            .put("contractFingerprint", MessageBusWorldMapContracts.fingerprint())
            .put("accepted", false)
            .put("rejectionReason", "VALIDATION_FAILED")
            .put("message", message)
            .build();
    }

    private WorldMapAuthorityReceipt receipt(
        MessageEnvelope envelope,
        MessageBusWorldMapContracts.Operation operation,
        String aggregateScope,
        String requestFingerprint,
        String resultFingerprint
    ) {
        String targetNode = firstKnown(envelope.getTargetId(), messageBus.currentServerId(), "authority");
        String originNode = firstKnown(envelope.getSenderId(), "unknown");
        return new WorldMapAuthorityReceipt(
            operation.name(),
            aggregateScope,
            targetNode,
            "messagebus:" + originNode + "->" + targetNode,
            "authority-world-map",
            MessageBusWorldMapContracts.schemaVersion(),
            MessageBusWorldMapContracts.fingerprint(),
            System.currentTimeMillis(),
            requestFingerprint,
            resultFingerprint
        );
    }

    private Map<String, Object> mapPayload(MessageEnvelope envelope) {
        if (envelope.getPayload() == null || envelope.getPayload().isNull()) {
            return Map.of();
        }
        Map<?, ?> raw = gson.fromJson(envelope.getPayload().toString(), Map.class);
        return stringObjectMap(raw);
    }

    private static Map<String, Object> stringObjectMap(Map<?, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key != null) {
                result.put(key.toString(), value);
            }
        });
        return result;
    }

    private static byte[] base64(Object value) {
        if (value == null || value.toString().isBlank()) {
            return new byte[0];
        }
        return Base64.getDecoder().decode(value.toString());
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static String firstKnown(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value)) {
                return value;
            }
        }
        return "unknown";
    }

    private static final class MapBuilder {
        private final Map<String, Object> values = new LinkedHashMap<>();

        private MapBuilder put(String key, Object value) {
            if (value != null) {
                values.put(key, value);
            }
            return this;
        }

        private Map<String, Object> build() {
            return Map.copyOf(values);
        }
    }
}
