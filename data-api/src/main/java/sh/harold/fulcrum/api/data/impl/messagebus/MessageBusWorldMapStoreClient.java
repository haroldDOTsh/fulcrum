package sh.harold.fulcrum.api.data.impl.messagebus;

import sh.harold.fulcrum.api.data.world.WorldMapStore;
import sh.harold.fulcrum.api.data.world.WorldMapStore.ReceiptedMapSaveResult;
import sh.harold.fulcrum.api.data.world.WorldMapStore.WorldMapAuthorityReceipt;
import sh.harold.fulcrum.api.data.world.WorldMapStore.WorldMapCatalogSnapshot;
import sh.harold.fulcrum.api.messagebus.MessageBus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class MessageBusWorldMapStoreClient implements WorldMapStore {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final MessageBus messageBus;
    private final String authorityServerId;
    private final Duration timeout;

    public MessageBusWorldMapStoreClient(MessageBus messageBus, String authorityServerId) {
        this(messageBus, authorityServerId, DEFAULT_TIMEOUT);
    }

    public MessageBusWorldMapStoreClient(MessageBus messageBus, String authorityServerId, Duration timeout) {
        this.messageBus = Objects.requireNonNull(messageBus, "messageBus");
        if (authorityServerId == null || authorityServerId.isBlank()) {
            throw new IllegalArgumentException("authorityServerId is required");
        }
        this.authorityServerId = authorityServerId;
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    }

    @Override
    public CompletionStage<List<WorldMapRecord>> loadWorlds() {
        return loadWorldCatalog().thenApply(WorldMapCatalogSnapshot::records);
    }

    @Override
    public CompletionStage<WorldMapCatalogSnapshot> loadWorldCatalog() {
        return messageBus.request(
                authorityServerId,
                MessageBusWorldMapChannels.LOAD,
                MessageBusWorldMapContracts.payload(MessageBusWorldMapContracts.Operation.LOAD, Map.of()),
                timeout)
            .thenApply(this::worldCatalog);
    }

    @Override
    public CompletionStage<MapSaveResult> saveWorldDefinition(
        String serverId,
        String worldName,
        String displayName,
        String metadataJson,
        byte[] schematicBytes
    ) {
        return saveWorldDefinitionWithReceipt(serverId, worldName, displayName, metadataJson, schematicBytes)
            .thenApply(ReceiptedMapSaveResult::result);
    }

    @Override
    public CompletionStage<ReceiptedMapSaveResult> saveWorldDefinitionWithReceipt(
        String serverId,
        String worldName,
        String displayName,
        String metadataJson,
        byte[] schematicBytes
    ) {
        validateSave(worldName, schematicBytes);
        Map<String, Object> payload = savePayload(serverId, worldName, displayName, metadataJson, schematicBytes);
        return messageBus.request(authorityServerId, MessageBusWorldMapChannels.SAVE,
                payload,
                timeout)
            .thenApply(raw -> saveResult(payload, raw));
    }

    private Map<String, Object> savePayload(
        String serverId,
        String worldName,
        String displayName,
        String metadataJson,
        byte[] schematicBytes
    ) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("serverId", serverId == null ? "" : serverId);
        values.put("worldName", worldName);
        if (displayName != null && !displayName.isBlank()) {
            values.put("displayName", displayName);
        }
        values.put("metadataJson", metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson);
        values.put("schematicBase64", Base64.getEncoder().encodeToString(schematicBytes));
        return MessageBusWorldMapContracts.payload(MessageBusWorldMapContracts.Operation.SAVE, values);
    }

    private WorldMapCatalogSnapshot worldCatalog(Object raw) {
        Map<?, ?> response = asMap(raw);
        rejectIfPresent(response);
        Object rawWorlds = response.get("worlds");
        if (!(rawWorlds instanceof Iterable<?> worlds)) {
            List<WorldMapRecord> emptyRecords = List.of();
            WorldMapAuthorityReceipt receipt = authorityReceipt(response, "LOAD");
            validateReceipt(
                receipt,
                "LOAD",
                WorldMapStore.CATALOG_SCOPE,
                WorldMapAuthorityReceipt.requestFingerprint("LOAD", WorldMapStore.CATALOG_SCOPE),
                WorldMapAuthorityReceipt.catalogFingerprint(emptyRecords)
            );
            return new WorldMapCatalogSnapshot(emptyRecords, receipt);
        }

        List<WorldMapRecord> records = new ArrayList<>();
        for (Object rawWorld : worlds) {
            Map<?, ?> world = asMap(rawWorld);
            records.add(new WorldMapRecord(
                uuid(world.get("id")),
                string(world.get("worldName")),
                string(world.get("displayName")),
                string(world.get("metadataJson")),
                base64(world.get("schematicBase64")),
                instant(world.get("updatedAtEpochMillis"))
            ));
        }
        List<WorldMapRecord> snapshot = List.copyOf(records);
        WorldMapAuthorityReceipt receipt = authorityReceipt(response, "LOAD");
        validateReceipt(
            receipt,
            "LOAD",
            WorldMapStore.CATALOG_SCOPE,
            WorldMapAuthorityReceipt.requestFingerprint("LOAD", WorldMapStore.CATALOG_SCOPE),
            WorldMapAuthorityReceipt.catalogFingerprint(snapshot)
        );
        return new WorldMapCatalogSnapshot(snapshot, receipt);
    }

    private ReceiptedMapSaveResult saveResult(Map<String, Object> requestPayload, Object raw) {
        Map<?, ?> response = asMap(raw);
        rejectIfPresent(response);
        MapSaveResult result = new MapSaveResult(
            uuid(response.get("id")),
            instant(response.get("updatedAtEpochMillis"))
        );
        String aggregateScope = WorldMapAuthorityReceipt.worldScope(string(requestPayload.get("worldName")));
        WorldMapAuthorityReceipt receipt = authorityReceipt(response, "SAVE");
        validateReceipt(
            receipt,
            "SAVE",
            aggregateScope,
            WorldMapAuthorityReceipt.requestFingerprint("SAVE", aggregateScope),
            WorldMapAuthorityReceipt.saveFingerprint(aggregateScope, result)
        );
        return new ReceiptedMapSaveResult(result, receipt);
    }

    private static Map<?, ?> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new IllegalArgumentException("Expected map response from world map transport");
    }

    private static void rejectIfPresent(Map<?, ?> response) {
        Object rejectionReason = response.get("rejectionReason");
        if (rejectionReason != null) {
            String message = string(response.get("message"));
            throw new IllegalStateException(message == null ? rejectionReason.toString() : message);
        }
    }

    private WorldMapAuthorityReceipt authorityReceipt(Map<?, ?> response, String operation) {
        Object rawReceipt = response.get("receipt");
        if (!(rawReceipt instanceof Map<?, ?> receipt)) {
            throw new IllegalStateException("World map " + operation + " response receipt is required");
        }
        try {
            return WorldMapAuthorityReceipt.fromPayload(receipt);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                "World map " + operation + " response receipt is invalid: " + exception.getMessage(),
                exception
            );
        }
    }

    private void validateReceipt(
        WorldMapAuthorityReceipt receipt,
        String operation,
        String aggregateScope,
        String requestFingerprint,
        String resultFingerprint
    ) {
        requireReceiptField(operation, "operation", operation, receipt.operation());
        requireReceiptField(operation, "aggregateScope", aggregateScope, receipt.aggregateScope());
        requireReceiptField(operation, "authorityNodeId", authorityServerId, receipt.authorityNodeId());
        requireReceiptField(operation, "schemaVersion", MessageBusWorldMapContracts.schemaVersion(),
            receipt.schemaVersion());
        requireReceiptField(operation, "contractFingerprint", MessageBusWorldMapContracts.fingerprint(),
            receipt.contractFingerprint());
        requireReceiptField(operation, "requestFingerprint", requestFingerprint, receipt.requestFingerprint());
        requireReceiptField(operation, "resultFingerprint", resultFingerprint, receipt.resultFingerprint());

        String currentServerId = messageBus.currentServerId();
        if (known(currentServerId)) {
            requireReceiptField(operation, "authorityRoute", "messagebus:" + currentServerId + "->" + authorityServerId,
                receipt.authorityRoute());
        }
    }

    private static void requireReceiptField(String operation, String field, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalStateException(
                "World map " + operation + " response receipt " + field + " mismatch: expected "
                    + expected + " but received " + actual
            );
        }
    }

    private static UUID uuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("UUID value is required");
        }
        return UUID.fromString(value.toString());
    }

    private static Instant instant(Object value) {
        return Instant.ofEpochMilli(longValue(value, 0L));
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? fallback : Long.parseLong(value.toString());
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

    private static boolean known(String value) {
        return value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value);
    }

    private static void validateSave(String worldName, byte[] schematicBytes) {
        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("worldName is required");
        }
        if (schematicBytes == null || schematicBytes.length == 0) {
            throw new IllegalArgumentException("schematicBytes cannot be empty");
        }
    }
}
