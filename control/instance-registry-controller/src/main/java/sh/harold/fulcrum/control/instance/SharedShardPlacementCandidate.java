package sh.harold.fulcrum.control.instance;

import sh.harold.fulcrum.api.kernel.SessionId;

import java.time.Instant;
import java.util.Objects;

public record SharedShardPlacementCandidate(
        InstanceSnapshot instanceSnapshot,
        SessionId sessionId,
        int currentOccupancy,
        int hardCapacity,
        Instant observedAt) {
    public SharedShardPlacementCandidate {
        instanceSnapshot = Objects.requireNonNull(instanceSnapshot, "instanceSnapshot");
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        if (currentOccupancy < 0) {
            throw new IllegalArgumentException("currentOccupancy must not be negative");
        }
        if (hardCapacity <= 0) {
            throw new IllegalArgumentException("hardCapacity must be positive");
        }
        if (currentOccupancy > hardCapacity) {
            throw new IllegalArgumentException("currentOccupancy must not exceed hardCapacity");
        }
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
    }

    boolean hasCapacityFor(SharedShardPlacementRequest request) {
        return currentOccupancy < Math.min(hardCapacity, request.hardCapacity());
    }
}
