package sh.harold.fulcrum.api.data.impl.authority.events;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable authority event row passed to registry-owned projection dispatch targets.
 */
public record AuthorityEventEnvelope(
    UUID eventId,
    UUID commandId,
    String aggregateScope,
    String aggregateType,
    String aggregateId,
    long revision,
    String eventType,
    Map<String, Object> payload,
    Map<String, Object> provenance,
    Instant createdAt
) {
    /**
     * Validates required event identity fields and freezes payload maps for dispatch consumers.
     *
     * @param eventId authority event id
     * @param commandId command id that produced the event
     * @param aggregateScope aggregate scope key
     * @param aggregateType aggregate type name
     * @param aggregateId aggregate id within its type
     * @param revision aggregate revision written by the authority
     * @param eventType event type name
     * @param payload event payload
     * @param provenance command route provenance
     * @param createdAt event creation timestamp
     */
    public AuthorityEventEnvelope {
        if (eventId == null) {
            throw new IllegalArgumentException("eventId is required");
        }
        if (commandId == null) {
            throw new IllegalArgumentException("commandId is required");
        }
        if (aggregateScope == null || aggregateScope.isBlank()) {
            throw new IllegalArgumentException("aggregateScope is required");
        }
        if (aggregateType == null || aggregateType.isBlank()) {
            throw new IllegalArgumentException("aggregateType is required");
        }
        if (aggregateId == null || aggregateId.isBlank()) {
            throw new IllegalArgumentException("aggregateId is required");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType is required");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt is required");
        }
        payload = immutableCopy(payload);
        provenance = immutableCopy(provenance);
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
