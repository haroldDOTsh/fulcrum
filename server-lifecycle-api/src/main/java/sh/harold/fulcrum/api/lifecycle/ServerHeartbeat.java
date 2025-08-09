package sh.harold.fulcrum.api.lifecycle;

import java.time.Instant;

/**
 * Unified heartbeat message containing all server metrics.
 */
public record ServerHeartbeat(
    String serverId,
    int playerCount,
    double tps,
    int softCap,
    int hardCap,
    Instant timestamp
) {
    /**
     * Creates a heartbeat with the current timestamp.
     */
    public static ServerHeartbeat create(
        String serverId,
        int playerCount,
        double tps,
        int softCap,
        int hardCap
    ) {
        return new ServerHeartbeat(
            serverId, playerCount, tps, 
            softCap, hardCap, Instant.now()
        );
    }

    /**
     * Checks if the server is healthy based on TPS.
     */
    public boolean isHealthy() {
        return tps >= 19.0;
    }

    /**
     * Checks if the server has reached soft capacity.
     */
    public boolean isSoftCapReached() {
        return playerCount >= softCap;
    }

    /**
     * Checks if the server has reached hard capacity.
     */
    public boolean isHardCapReached() {
        return playerCount >= hardCap;
    }

    /**
     * Gets available slots before soft cap.
     */
    public int availableSoftSlots() {
        return Math.max(0, softCap - playerCount);
    }

    /**
     * Gets available slots before hard cap.
     */
    public int availableHardSlots() {
        return Math.max(0, hardCap - playerCount);
    }
}