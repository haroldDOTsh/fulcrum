package sh.harold.fulcrum.api.data.world;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Narrow world-map persistence boundary owned by the authority tier.
 */
public interface WorldMapStore {
    String CATALOG_SCOPE = "world-map:catalog";

    CompletionStage<List<WorldMapRecord>> loadWorlds();

    default CompletionStage<WorldMapCatalogSnapshot> loadWorldCatalog() {
        return loadWorlds().thenApply(records -> {
            List<WorldMapRecord> safeRecords = records == null ? List.of() : List.copyOf(records);
            return new WorldMapCatalogSnapshot(
                safeRecords,
                WorldMapAuthorityReceipt.localRead(safeRecords)
            );
        });
    }

    CompletionStage<MapSaveResult> saveWorldDefinition(
        String serverId,
        String worldName,
        String displayName,
        String metadataJson,
        byte[] schematicBytes
    );

    default CompletionStage<ReceiptedMapSaveResult> saveWorldDefinitionWithReceipt(
        String serverId,
        String worldName,
        String displayName,
        String metadataJson,
        byte[] schematicBytes
    ) {
        return saveWorldDefinition(serverId, worldName, displayName, metadataJson, schematicBytes)
            .thenApply(result -> new ReceiptedMapSaveResult(
                result,
                WorldMapAuthorityReceipt.localSave(worldName, result)
            ));
    }

    record WorldMapRecord(
        UUID id,
        String worldName,
        String displayName,
        String metadataJson,
        byte[] schematicBytes,
        Instant updatedAt
    ) {
        public WorldMapRecord {
            Objects.requireNonNull(id, "id");
            if (worldName == null || worldName.isBlank()) {
                throw new IllegalArgumentException("worldName is required");
            }
            metadataJson = metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson;
            schematicBytes = schematicBytes == null ? new byte[0] : schematicBytes.clone();
            updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
        }

        @Override
        public byte[] schematicBytes() {
            return schematicBytes.clone();
        }
    }

    record MapSaveResult(UUID id, Instant updatedAt) {
        public MapSaveResult {
            Objects.requireNonNull(id, "id");
            updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
        }
    }

    record WorldMapCatalogSnapshot(
        List<WorldMapRecord> records,
        WorldMapAuthorityReceipt receipt
    ) {
        public WorldMapCatalogSnapshot {
            records = records == null ? List.of() : List.copyOf(records);
            receipt = Objects.requireNonNull(receipt, "receipt");
        }
    }

    record ReceiptedMapSaveResult(
        MapSaveResult result,
        WorldMapAuthorityReceipt receipt
    ) {
        public ReceiptedMapSaveResult {
            result = Objects.requireNonNull(result, "result");
            receipt = Objects.requireNonNull(receipt, "receipt");
        }
    }

    record WorldMapAuthorityReceipt(
        String operation,
        String aggregateScope,
        String authorityNodeId,
        String authorityRoute,
        String sourceTier,
        int schemaVersion,
        String contractFingerprint,
        long observedAtEpochMillis,
        String requestFingerprint,
        String resultFingerprint
    ) {
        private static final String HASH_PATTERN = "[0-9a-f]{64}";

        public WorldMapAuthorityReceipt {
            operation = requireText(operation, "operation");
            aggregateScope = requireText(aggregateScope, "aggregateScope");
            authorityNodeId = requireText(authorityNodeId, "authorityNodeId");
            authorityRoute = requireText(authorityRoute, "authorityRoute");
            sourceTier = requireText(sourceTier, "sourceTier");
            if (schemaVersion <= 0) {
                throw new IllegalArgumentException("schemaVersion must be positive");
            }
            requireHash(contractFingerprint, "contractFingerprint");
            if (observedAtEpochMillis < 0L) {
                throw new IllegalArgumentException("observedAtEpochMillis must not be negative");
            }
            requireHash(requestFingerprint, "requestFingerprint");
            requireHash(resultFingerprint, "resultFingerprint");
        }

        public Map<String, Object> payload() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("operation", operation);
            values.put("aggregateScope", aggregateScope);
            values.put("authorityNodeId", authorityNodeId);
            values.put("authorityRoute", authorityRoute);
            values.put("sourceTier", sourceTier);
            values.put("schemaVersion", schemaVersion);
            values.put("contractFingerprint", contractFingerprint);
            values.put("observedAtEpochMillis", observedAtEpochMillis);
            values.put("requestFingerprint", requestFingerprint);
            values.put("resultFingerprint", resultFingerprint);
            return Map.copyOf(values);
        }

        public static WorldMapAuthorityReceipt fromPayload(Map<?, ?> payload) {
            if (payload == null || payload.isEmpty()) {
                throw new IllegalArgumentException("World map authority receipt is required");
            }
            return new WorldMapAuthorityReceipt(
                string(payload.get("operation")),
                string(payload.get("aggregateScope")),
                string(payload.get("authorityNodeId")),
                string(payload.get("authorityRoute")),
                string(payload.get("sourceTier")),
                intValue(payload.get("schemaVersion"), 0),
                string(payload.get("contractFingerprint")),
                longValue(payload.get("observedAtEpochMillis"), -1L),
                string(payload.get("requestFingerprint")),
                string(payload.get("resultFingerprint"))
            );
        }

        public static WorldMapAuthorityReceipt localRead(List<WorldMapRecord> records) {
            List<WorldMapRecord> safeRecords = records == null ? List.of() : List.copyOf(records);
            return new WorldMapAuthorityReceipt(
                "LOAD",
                CATALOG_SCOPE,
                "local-world-map-store",
                "local:world-map-store",
                "local-store",
                1,
                hash("local-world-map-contract"),
                System.currentTimeMillis(),
                requestFingerprint("LOAD", CATALOG_SCOPE),
                catalogFingerprint(safeRecords)
            );
        }

        public static WorldMapAuthorityReceipt localSave(String worldName, MapSaveResult result) {
            String aggregateScope = worldScope(worldName);
            return new WorldMapAuthorityReceipt(
                "SAVE",
                aggregateScope,
                "local-world-map-store",
                "local:world-map-store",
                "local-store",
                1,
                hash("local-world-map-contract"),
                System.currentTimeMillis(),
                requestFingerprint("SAVE", aggregateScope),
                saveFingerprint(aggregateScope, result)
            );
        }

        public static String requestFingerprint(String operation, String aggregateScope) {
            return hash(requireText(operation, "operation") + "|" + requireText(aggregateScope, "aggregateScope"));
        }

        public static String catalogFingerprint(List<WorldMapRecord> records) {
            StringBuilder material = new StringBuilder("world-map-catalog\n");
            (records == null ? List.<WorldMapRecord>of() : records).stream()
                .sorted(Comparator
                    .comparing(WorldMapRecord::worldName, Comparator.nullsLast(String::compareTo))
                    .thenComparing(record -> record.id().toString()))
                .forEach(record -> material
                    .append(record.id()).append('|')
                    .append(record.worldName()).append('|')
                    .append(record.displayName()).append('|')
                    .append(record.metadataJson()).append('|')
                    .append(hashBytes(record.schematicBytes())).append('|')
                    .append(record.updatedAt().toEpochMilli())
                    .append('\n'));
            return hash(material.toString());
        }

        public static String saveFingerprint(String aggregateScope, MapSaveResult result) {
            Objects.requireNonNull(result, "result");
            return hash(requireText(aggregateScope, "aggregateScope") + '|'
                + result.id() + '|'
                + result.updatedAt().toEpochMilli());
        }

        public static String worldScope(String worldName) {
            return "world-map:" + requireText(worldName, "worldName");
        }

        public static String hash(String value) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        }

        private static String hashBytes(byte[] bytes) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(digest.digest(bytes == null ? new byte[0] : bytes));
            } catch (Exception exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        }

        private static void requireHash(String value, String field) {
            if (value == null || !value.matches(HASH_PATTERN)) {
                throw new IllegalArgumentException(field + " must be a SHA-256 hash");
            }
        }

        private static String requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return value;
        }

        private static int intValue(Object value, int fallback) {
            if (value instanceof Number number) {
                return number.intValue();
            }
            return value == null || value.toString().isBlank() ? fallback : Integer.parseInt(value.toString());
        }

        private static long longValue(Object value, long fallback) {
            if (value instanceof Number number) {
                return number.longValue();
            }
            return value == null || value.toString().isBlank() ? fallback : Long.parseLong(value.toString());
        }

        private static String string(Object value) {
            return value == null ? null : value.toString();
        }
    }
}
