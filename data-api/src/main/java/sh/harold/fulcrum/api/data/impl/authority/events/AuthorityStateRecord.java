package sh.harold.fulcrum.api.data.impl.authority.events;

import com.google.gson.Gson;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityLogRecord;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityLogTopicKind;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityLogTopology;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * One compacted state-topic record used as a restore source for hot projections.
 */
public record AuthorityStateRecord(
    UUID commandId,
    UUID eventId,
    String aggregateScope,
    String aggregateType,
    String aggregateId,
    long revision,
    String commandDomain,
    String stateTopic,
    String partitionKey,
    int sourcePartition,
    long sourceOffset,
    Map<String, Object> statePayload,
    String stateFingerprint,
    String eventChainHash,
    Instant eventCreatedAt
) {
    private static final Gson GSON = new Gson();

    public AuthorityStateRecord(
        UUID commandId,
        UUID eventId,
        String aggregateScope,
        String aggregateType,
        String aggregateId,
        long revision,
        String commandDomain,
        String stateTopic,
        String partitionKey,
        Map<String, Object> statePayload,
        String stateFingerprint,
        String eventChainHash,
        Instant eventCreatedAt
    ) {
        this(
            commandId,
            eventId,
            aggregateScope,
            aggregateType,
            aggregateId,
            revision,
            commandDomain,
            stateTopic,
            partitionKey,
            sourcePartition(commandDomain, partitionKey),
            Math.max(0L, revision - 1L),
            statePayload,
            stateFingerprint,
            eventChainHash,
            eventCreatedAt
        );
    }

    public AuthorityStateRecord {
        commandId = Objects.requireNonNull(commandId, "commandId");
        eventId = Objects.requireNonNull(eventId, "eventId");
        aggregateScope = requireText(aggregateScope, "aggregateScope");
        aggregateType = requireText(aggregateType, "aggregateType");
        aggregateId = requireText(aggregateId, "aggregateId");
        if (revision <= 0L) {
            throw new IllegalArgumentException("revision must be positive");
        }
        commandDomain = requireText(commandDomain, "commandDomain");
        stateTopic = requireText(stateTopic, "stateTopic");
        partitionKey = requireText(partitionKey, "partitionKey");
        sourcePartition = sourcePartition < 0 ? -1 : sourcePartition;
        sourceOffset = sourceOffset < 0L ? -1L : sourceOffset;
        statePayload = immutableCopy(statePayload);
        stateFingerprint = requireFingerprint(stateFingerprint, "stateFingerprint");
        eventChainHash = requireFingerprint(eventChainHash, "eventChainHash");
        eventCreatedAt = Objects.requireNonNull(eventCreatedAt, "eventCreatedAt");
    }

    public boolean hasValidStateFingerprint() {
        return stateFingerprint.equals(stateFingerprint(statePayload));
    }

    public static AuthorityStateRecord fromLogRecord(AuthorityLogRecord record) {
        Objects.requireNonNull(record, "record");
        if (record.kind() != AuthorityLogTopicKind.STATE) {
            throw new IllegalArgumentException("authority log record must be a state frame");
        }
        return fromLogPayload(record.payload(), record.partition(), record.offset());
    }

    public static AuthorityStateRecord fromLogPayload(
        Map<?, ?> raw,
        int sourcePartition,
        long sourceOffset
    ) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("state log payload is required");
        }
        Map<?, ?> watermark = map(raw.get("watermark"));
        Map<?, ?> settlement = map(raw.get("settlement"));
        Map<String, Object> statePayload = objectMap(first(raw.get("statePayload"), settlement.get("statePayload")));
        if (statePayload.isEmpty()) {
            throw new IllegalArgumentException("state log payload is missing statePayload");
        }
        return new AuthorityStateRecord(
            uuid(first(raw.get("commandId"), watermark.get("sourceCommandId")), "commandId"),
            uuid(first(raw.get("eventId"), watermark.get("sourceEventId")), "eventId"),
            text(first(raw.get("aggregateScope"), watermark.get("aggregateScope")), "aggregateScope"),
            text(first(raw.get("aggregateType"), watermark.get("aggregateType")), "aggregateType"),
            text(first(raw.get("aggregateId"), watermark.get("aggregateId")), "aggregateId"),
            longValue(first(raw.get("revision"), watermark.get("sourceRevision")), "revision"),
            text(
                first(raw.get("commandDomain"), settlement.get("commandDomain"), watermark.get("commandDomain")),
                "commandDomain"
            ),
            text(first(raw.get("stateTopic"), settlement.get("stateTopic"), watermark.get("stateTopic")), "stateTopic"),
            text(
                first(raw.get("partitionKey"), settlement.get("partitionKey"), watermark.get("partitionKey")),
                "partitionKey"
            ),
            sourcePartition,
            sourceOffset,
            statePayload,
            text(first(raw.get("stateFingerprint"), watermark.get("stateFingerprint")), "stateFingerprint"),
            text(first(raw.get("eventChainHash"), watermark.get("eventChainHash")), "eventChainHash"),
            Instant.ofEpochMilli(longValue(
                first(raw.get("eventCreatedEpochMillis"), watermark.get("eventCreatedEpochMillis")),
                "eventCreatedEpochMillis"
            ))
        );
    }

    public static String stateFingerprint(Map<String, Object> statePayload) {
        return sha256(GSON.toJson(canonicalValue(statePayload == null ? Map.of() : statePayload)));
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static Object canonicalValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, child) -> {
                if (key != null) {
                    sorted.put(key.toString(), canonicalValue(child));
                }
            });
            return sorted;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> values = new ArrayList<>();
            for (Object child : iterable) {
                values.add(canonicalValue(child));
            }
            return values;
        }
        if (value instanceof Number number) {
            return canonicalNumber(number);
        }
        if (value instanceof UUID uuid) {
            return uuid.toString();
        }
        return value;
    }

    private static String canonicalNumber(Number number) {
        if (number instanceof Byte || number instanceof Short
            || number instanceof Integer || number instanceof Long) {
            return BigDecimal.valueOf(number.longValue()).toPlainString();
        }
        return BigDecimal.valueOf(number.doubleValue()).stripTrailingZeros().toPlainString();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String requireFingerprint(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a SHA-256 hash");
        }
        return value;
    }

    private static int sourcePartition(String commandDomain, String partitionKey) {
        if (commandDomain == null || commandDomain.isBlank() || partitionKey == null || partitionKey.isBlank()) {
            return -1;
        }
        return AuthorityLogTopology.partition(commandDomain, partitionKey);
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, child) -> {
            if (key != null) {
                result.put(key.toString(), child);
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private static Object first(Object... values) {
        for (Object value : values) {
            if (value != null && !value.toString().isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static UUID uuid(Object value, String field) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return UUID.fromString(value.toString());
    }

    private static String text(Object value, String field) {
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.toString();
    }

    private static long longValue(Object value, String field) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return Long.parseLong(value.toString());
    }
}
