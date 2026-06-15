package sh.harold.fulcrum.api.data.impl.authority;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One append to the authority log, addressed by topic, aggregate key, partition, and offset.
 */
public record AuthorityLogRecord(
    String topic,
    String key,
    int partition,
    long offset,
    AuthorityLogTopicKind kind,
    Map<String, Object> payload,
    Map<String, String> headers,
    long appendedAtEpochMillis
) {
    public AuthorityLogRecord(
        String topic,
        String key,
        int partition,
        long offset,
        AuthorityLogTopicKind kind,
        Map<String, Object> payload,
        long appendedAtEpochMillis
    ) {
        this(topic, key, partition, offset, kind, payload, Map.of(), appendedAtEpochMillis);
    }

    public AuthorityLogRecord {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic is required");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key is required");
        }
        if (partition < 0) {
            throw new IllegalArgumentException("partition must be non-negative");
        }
        if (offset < 0L) {
            throw new IllegalArgumentException("offset must be non-negative");
        }
        kind = java.util.Objects.requireNonNull(kind, "kind");
        payload = immutableCopy(payload);
        headers = immutableStringCopy(headers);
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static Map<String, String> immutableStringCopy(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
