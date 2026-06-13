package sh.harold.fulcrum.registry.coordination;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Ephemeral coordination state for registry decisions that must survive more
 * than one JVM's local maps, but should not become durable database state.
 */
public interface RegistryCoordinationStore extends AutoCloseable {

    Optional<CapacityLease> reserveCapacity(
        String serverId,
        String familyId,
        int capacity,
        Duration ttl,
        Map<String, String> metadata
    );

    void releaseCapacity(CapacityLease lease);

    @Override
    default void close() {
    }

    record CapacityLease(
        String ticketId,
        String serverId,
        String familyId,
        long expiresAtEpochMillis,
        Map<String, String> metadata
    ) {
        public CapacityLease {
            if (ticketId == null || ticketId.isBlank()) {
                ticketId = UUID.randomUUID().toString();
            }
            if (serverId == null || serverId.isBlank()) {
                throw new IllegalArgumentException("serverId is required");
            }
            if (familyId == null || familyId.isBlank()) {
                throw new IllegalArgumentException("familyId is required");
            }
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
