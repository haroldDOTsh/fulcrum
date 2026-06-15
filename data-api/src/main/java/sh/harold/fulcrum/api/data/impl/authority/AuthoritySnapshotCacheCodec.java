package sh.harold.fulcrum.api.data.impl.authority;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.events.AuthorityStateRecord;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Stable Valkey snapshot-cache wire format for authority hot reads.
 */
public final class AuthoritySnapshotCacheCodec {
    public static final String STORE_NAME = "valkey";
    public static final String WIRE_PROTOCOL = "redis-compatible";
    public static final String PROJECTION_NAME = "valkey-authority-snapshot-cache";
    public static final String KEY_PREFIX = "fulcrum:authority:valkey:snapshot:";

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    private AuthoritySnapshotCacheCodec() {
    }

    public static String cacheKey(String projectionFamily, String aggregateScope) {
        String normalizedProjection = requireText(projectionFamily, "projectionFamily");
        String normalizedScope = requireText(aggregateScope, "aggregateScope");
        String encodedScope = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(normalizedScope.getBytes(StandardCharsets.UTF_8));
        return KEY_PREFIX + normalizedProjection + ":" + encodedScope;
    }

    public static Map<String, String> fields(AuthorityStateRecord record, long cachedAtEpochMillis) {
        AuthorityStateRecord stateRecord = requireRecord(record);
        DataAuthority.SnapshotWatermark watermark = watermark(stateRecord);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("projectionFamily", stateRecord.aggregateType());
        fields.put("aggregateScope", stateRecord.aggregateScope());
        fields.put("aggregateType", stateRecord.aggregateType());
        fields.put("aggregateId", stateRecord.aggregateId());
        fields.put("revision", Long.toString(stateRecord.revision()));
        fields.put("stateTopic", stateRecord.stateTopic());
        fields.put("partitionKey", stateRecord.partitionKey());
        fields.put("sourcePartition", Integer.toString(stateRecord.sourcePartition()));
        fields.put("sourceOffset", Long.toString(stateRecord.sourceOffset()));
        fields.put("eventCreatedEpochMillis", Long.toString(stateRecord.eventCreatedAt().toEpochMilli()));
        fields.put("cachedAtEpochMillis", Long.toString(Math.max(0L, cachedAtEpochMillis)));
        fields.put("stateFingerprint", stateRecord.stateFingerprint());
        fields.put("eventChainHash", stateRecord.eventChainHash());
        fields.put("statePayload", GSON.toJson(stateRecord.statePayload()));
        fields.put("watermark", GSON.toJson(watermark.payload()));
        return Map.copyOf(fields);
    }

    public static Map<String, String> profileFields(
        AuthoritySnapshotCacheStore.SnapshotLine<DataAuthority.PlayerProfileSnapshot> line
    ) {
        AuthoritySnapshotCacheStore.SnapshotLine<DataAuthority.PlayerProfileSnapshot> snapshotLine =
            requireLine(line, "player_profile");
        DataAuthority.PlayerProfileSnapshot snapshot = snapshotLine.snapshot();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("playerId", snapshot.playerId().toString());
        payload.put("username", snapshot.username());
        payload.put("normalizedUsername", snapshot.normalizedUsername());
        payload.put("online", snapshot.online());
        payload.put("currentServer", snapshot.currentServer());
        payload.put("currentProxy", snapshot.currentProxy());
        payload.put("totalPlaytimeMs", snapshot.totalPlaytimeMs());
        payload.put("profileData", snapshot.profileData());
        payload.put("revision", snapshot.revision());
        return fields(
            snapshotLine.projectionFamily(),
            snapshotLine.aggregateScope(),
            "player_profile",
            snapshot.playerId().toString(),
            snapshot.revision(),
            snapshot.watermark(),
            payload,
            snapshotLine.cachedAtEpochMillis()
        );
    }

    public static Map<String, String> rankFields(
        AuthoritySnapshotCacheStore.SnapshotLine<DataAuthority.PlayerRankSnapshot> line
    ) {
        AuthoritySnapshotCacheStore.SnapshotLine<DataAuthority.PlayerRankSnapshot> snapshotLine =
            requireLine(line, "player_rank");
        DataAuthority.PlayerRankSnapshot snapshot = snapshotLine.snapshot();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("playerId", snapshot.playerId().toString());
        payload.put("primaryRank", snapshot.primaryRank());
        payload.put("ranks", snapshot.ranks());
        payload.put("revision", snapshot.revision());
        return fields(
            snapshotLine.projectionFamily(),
            snapshotLine.aggregateScope(),
            "player_rank",
            snapshot.playerId().toString(),
            snapshot.revision(),
            snapshot.watermark(),
            payload,
            snapshotLine.cachedAtEpochMillis()
        );
    }

    public static long cachedAtEpochMillis(Map<String, String> fields) {
        return longValue(safeFields(fields).get("cachedAtEpochMillis"), 0L);
    }

    public static Optional<DataAuthority.PlayerProfileSnapshot> profileSnapshot(Map<String, String> fields) {
        Map<String, String> safeFields = safeFields(fields);
        if (safeFields.isEmpty() || !"player_profile".equals(safeFields.get("projectionFamily"))) {
            return Optional.empty();
        }
        Map<String, Object> payload = statePayload(safeFields);
        UUID playerId = uuid(first(payload.get("playerId"), safeFields.get("aggregateId")));
        if (playerId == null) {
            return Optional.empty();
        }
        String username = string(payload.get("username"), "unknown");
        return Optional.of(new DataAuthority.PlayerProfileSnapshot(
            playerId,
            username,
            string(payload.get("normalizedUsername"), username.toLowerCase(Locale.ROOT)),
            booleanValue(payload.get("online")),
            string(payload.get("currentServer"), null),
            string(payload.get("currentProxy"), null),
            longValue(payload.get("totalPlaytimeMs"), 0L),
            stringObjectMap(payload.get("profileData")),
            longValue(first(payload.get("revision"), safeFields.get("revision")), 0L),
            watermark(safeFields)
        ));
    }

    public static Optional<DataAuthority.PlayerRankSnapshot> rankSnapshot(Map<String, String> fields) {
        Map<String, String> safeFields = safeFields(fields);
        if (safeFields.isEmpty() || !"player_rank".equals(safeFields.get("projectionFamily"))) {
            return Optional.empty();
        }
        Map<String, Object> payload = statePayload(safeFields);
        UUID playerId = uuid(first(payload.get("playerId"), safeFields.get("aggregateId")));
        if (playerId == null) {
            return Optional.empty();
        }
        String primaryRank = string(payload.get("primaryRank"), "DEFAULT");
        List<String> ranks = strings(payload.get("ranks"));
        return Optional.of(new DataAuthority.PlayerRankSnapshot(
            playerId,
            primaryRank,
            ranks.isEmpty() ? List.of(primaryRank) : ranks,
            longValue(first(payload.get("revision"), safeFields.get("revision")), 0L),
            watermark(safeFields)
        ));
    }

    static DataAuthority.SnapshotWatermark watermark(AuthorityStateRecord record) {
        AuthorityStateRecord stateRecord = requireRecord(record);
        return new DataAuthority.SnapshotWatermark(
            PROJECTION_NAME,
            stateRecord.aggregateScope(),
            stateRecord.aggregateType(),
            stateRecord.aggregateId(),
            stateRecord.commandDomain(),
            stateRecord.stateTopic(),
            stateRecord.partitionKey(),
            stateRecord.commandId(),
            stateRecord.eventId(),
            stateRecord.revision(),
            stateRecord.eventCreatedAt().toEpochMilli(),
            stateRecord.sourcePartition(),
            stateRecord.sourceOffset(),
            stateRecord.stateFingerprint(),
            stateRecord.eventChainHash()
        );
    }

    private static DataAuthority.SnapshotWatermark watermark(Map<String, String> fields) {
        Map<?, ?> raw = mapPayload(fields.get("watermark"));
        DataAuthority.SnapshotWatermark fallback = new DataAuthority.SnapshotWatermark(
            PROJECTION_NAME,
            fields.get("aggregateScope"),
            fields.get("aggregateType"),
            fields.get("aggregateId"),
            "unknown",
            fields.get("stateTopic"),
            fields.get("partitionKey"),
            null,
            null,
            longValue(fields.get("revision"), 0L),
            longValue(fields.get("eventCreatedEpochMillis"), 0L),
            intValue(fields.get("sourcePartition"), -1),
            longValue(fields.get("sourceOffset"), -1L),
            string(fields.get("stateFingerprint"), "unknown"),
            string(fields.get("eventChainHash"), "unknown")
        );
        return DataAuthority.SnapshotWatermark.fromPayload(raw, fallback);
    }

    private static Map<String, String> fields(
        String projectionFamily,
        String aggregateScope,
        String aggregateType,
        String aggregateId,
        long revision,
        DataAuthority.SnapshotWatermark watermark,
        Map<String, Object> statePayload,
        long cachedAtEpochMillis
    ) {
        DataAuthority.SnapshotWatermark snapshotWatermark = requireWatermark(watermark, projectionFamily, aggregateScope);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("projectionFamily", requireText(projectionFamily, "projectionFamily"));
        fields.put("aggregateScope", requireText(aggregateScope, "aggregateScope"));
        fields.put("aggregateType", requireText(aggregateType, "aggregateType"));
        fields.put("aggregateId", requireText(aggregateId, "aggregateId"));
        fields.put("revision", Long.toString(Math.max(0L, revision)));
        fields.put("stateTopic", snapshotWatermark.stateTopic());
        fields.put("partitionKey", snapshotWatermark.partitionKey());
        fields.put("sourcePartition", Integer.toString(snapshotWatermark.sourcePartition()));
        fields.put("sourceOffset", Long.toString(snapshotWatermark.sourceOffset()));
        fields.put("eventCreatedEpochMillis", Long.toString(snapshotWatermark.eventCreatedEpochMillis()));
        fields.put("cachedAtEpochMillis", Long.toString(Math.max(0L, cachedAtEpochMillis)));
        fields.put("stateFingerprint", snapshotWatermark.stateFingerprint());
        fields.put("eventChainHash", snapshotWatermark.eventChainHash());
        fields.put("statePayload", GSON.toJson(statePayload == null ? Map.of() : statePayload));
        fields.put("watermark", GSON.toJson(snapshotWatermark.payload()));
        return Map.copyOf(fields);
    }

    private static <T> AuthoritySnapshotCacheStore.SnapshotLine<T> requireLine(
        AuthoritySnapshotCacheStore.SnapshotLine<T> line,
        String projectionFamily
    ) {
        if (line == null || !line.serveable()) {
            throw new IllegalArgumentException("snapshot cache line is not serveable");
        }
        if (!projectionFamily.equals(line.projectionFamily())) {
            throw new IllegalArgumentException("snapshot cache line projection family mismatch");
        }
        requireWatermark(line.watermark(), projectionFamily, line.aggregateScope());
        return line;
    }

    private static DataAuthority.SnapshotWatermark requireWatermark(
        DataAuthority.SnapshotWatermark watermark,
        String projectionFamily,
        String aggregateScope
    ) {
        if (watermark == null || !watermark.watermarked()) {
            throw new IllegalArgumentException("snapshot cache line must be authority-watermarked");
        }
        if (!requireText(projectionFamily, "projectionFamily").equals(watermark.aggregateType())) {
            throw new IllegalArgumentException("snapshot watermark projection family mismatch");
        }
        if (!requireText(aggregateScope, "aggregateScope").equals(watermark.aggregateScope())) {
            throw new IllegalArgumentException("snapshot watermark aggregate scope mismatch");
        }
        if (!DataAuthorityReadContracts.stateTopicMatches(projectionFamily, watermark.stateTopic())) {
            throw new IllegalArgumentException("snapshot watermark state topic mismatch");
        }
        return watermark;
    }

    private static Map<String, Object> statePayload(Map<String, String> fields) {
        return stringObjectMap(mapPayload(fields.get("statePayload")));
    }

    private static Map<?, ?> mapPayload(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        Object parsed = GSON.fromJson(raw, MAP_TYPE);
        return parsed instanceof Map<?, ?> map ? map : Map.of();
    }

    private static Map<String, String> safeFields(Map<String, String> fields) {
        return fields == null || fields.isEmpty() ? Map.of() : Map.copyOf(fields);
    }

    private static AuthorityStateRecord requireRecord(AuthorityStateRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record is required");
        }
        if (!record.hasValidStateFingerprint()) {
            throw new IllegalArgumentException("state record fingerprint does not match state payload");
        }
        return record;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static Object first(Object... values) {
        for (Object value : values) {
            if (value != null && !value.toString().isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static UUID uuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return UUID.fromString(value.toString());
    }

    private static String string(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null || value.toString().isBlank() ? fallback : Long.parseLong(value.toString());
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null || value.toString().isBlank() ? fallback : Integer.parseInt(value.toString());
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (Object child : iterable) {
            if (child != null && !child.toString().isBlank()) {
                values.add(child.toString());
            }
        }
        return List.copyOf(values);
    }

    private static Map<String, Object> stringObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, child) -> {
            if (key != null) {
                result.put(key.toString(), child);
            }
        });
        return Map.copyOf(result);
    }
}
