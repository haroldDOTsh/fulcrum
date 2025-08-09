package sh.harold.fulcrum.api.lifecycle;

import java.time.Instant;
import java.util.UUID;

/**
 * Core server information.
 */
public record ServerMetadata(
    String id,
    String family,
    ServerType type,
    String address,
    int port,
    ServerStatus status,
    Instant registeredAt,
    Instant lastHeartbeat,
    UUID instanceUuid
) {
    /**
     * Creates a new ServerMetadata with updated status.
     */
    public ServerMetadata withStatus(ServerStatus newStatus) {
        return new ServerMetadata(
            id, family, type, address, port, 
            newStatus, registeredAt, lastHeartbeat, instanceUuid
        );
    }

    /**
     * Creates a new ServerMetadata with updated heartbeat time.
     */
    public ServerMetadata withHeartbeat(Instant heartbeatTime) {
        return new ServerMetadata(
            id, family, type, address, port,
            status, registeredAt, heartbeatTime, instanceUuid
        );
    }

    /**
     * Checks if this server is considered crashed based on heartbeat timeout.
     */
    public boolean isCrashed(int timeoutSeconds) {
        if (lastHeartbeat == null) {
            return false;
        }
        return Instant.now().isAfter(lastHeartbeat.plusSeconds(timeoutSeconds));
    }
}