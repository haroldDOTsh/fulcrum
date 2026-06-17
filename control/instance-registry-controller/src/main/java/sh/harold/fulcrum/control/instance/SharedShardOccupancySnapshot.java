package sh.harold.fulcrum.control.instance;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;

import java.time.Instant;
import java.util.Objects;

public record SharedShardOccupancySnapshot(
        SessionId sessionId,
        SlotId slotId,
        int currentPresences,
        int hardCapacity,
        boolean acceptingPresences,
        Instant observedAt,
        TraceEnvelope traceEnvelope) {
    public SharedShardOccupancySnapshot {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        slotId = Objects.requireNonNull(slotId, "slotId");
        if (currentPresences < 0) {
            throw new IllegalArgumentException("currentPresences must not be negative");
        }
        if (hardCapacity <= 0) {
            throw new IllegalArgumentException("hardCapacity must be positive");
        }
        if (currentPresences > hardCapacity) {
            throw new IllegalArgumentException("currentPresences must not exceed hardCapacity");
        }
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }

    boolean hasCapacityBelow(int descriptorHardCapacity) {
        return acceptingPresences
                && currentPresences < Math.min(hardCapacity, descriptorHardCapacity);
    }
}
