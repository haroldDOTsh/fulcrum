package sh.harold.fulcrum.control.instance;

import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;

import java.time.Instant;
import java.util.Objects;

public record SharedShardPlacementCandidate(
        InstanceSnapshot instanceSnapshot,
        SharedShardOccupancySnapshot occupancySnapshot) {
    public SharedShardPlacementCandidate {
        instanceSnapshot = Objects.requireNonNull(instanceSnapshot, "instanceSnapshot");
        occupancySnapshot = Objects.requireNonNull(occupancySnapshot, "occupancySnapshot");
    }

    boolean hasCapacityFor(SharedShardPlacementRequest request) {
        return occupancySnapshot.hasCapacityBelow(request.experience().hardCapacity());
    }

    SessionId sessionId() {
        return occupancySnapshot.sessionId();
    }

    SlotId slotId() {
        return occupancySnapshot.slotId();
    }

    int currentOccupancy() {
        return occupancySnapshot.currentPresences();
    }

    int hardCapacity() {
        return occupancySnapshot.hardCapacity();
    }

    Instant observedAt() {
        return occupancySnapshot.observedAt();
    }
}
