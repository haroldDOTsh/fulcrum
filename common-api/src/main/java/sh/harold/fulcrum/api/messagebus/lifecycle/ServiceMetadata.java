package sh.harold.fulcrum.api.messagebus.lifecycle;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Metadata associated with a service, including metrics and custom properties.
 */
public class ServiceMetadata {

    private final Map<String, Object> customProperties = new ConcurrentHashMap<>();
    private int playerCount = 0;
    private int maxCapacity = 100;
    private double tps = 20.0;
    private ServiceStatus status = ServiceStatus.STARTING;
    private long lastHeartbeat = System.currentTimeMillis();
    // Server-specific metadata
    private int softCap = 80;
    private int hardCap = 100;

    public ServiceMetadata() {
    }

    public ServiceMetadata(int maxCapacity) {
        this.maxCapacity = maxCapacity;
        this.hardCap = maxCapacity;
        this.softCap = (int) (maxCapacity * 0.8);
    }

    /**
     * Update metrics from heartbeat data.
     */
    public void updateMetrics(int playerCount, double tps) {
        this.playerCount = playerCount;
        this.tps = Math.min(tps, 20.0); // Cap at 20 TPS
        this.lastHeartbeat = System.currentTimeMillis();
    }

    /**
     * Check if the service has timed out (no heartbeat for specified duration).
     */
    public boolean hasTimedOut(long timeoutMillis) {
        return (System.currentTimeMillis() - lastHeartbeat) > timeoutMillis;
    }

    /**
     * Get seconds since last heartbeat.
     */
    public long getSecondsSinceHeartbeat() {
        return (System.currentTimeMillis() - lastHeartbeat) / 1000;
    }

    /**
     * Check if service is at soft capacity.
     */
    public boolean isAtSoftCap() {
        return playerCount >= softCap;
    }

    /**
     * Check if service is at hard capacity.
     */
    public boolean isAtHardCap() {
        return playerCount >= hardCap;
    }

    /**
     * Get load percentage (0-100).
     */
    public int getLoadPercentage() {
        if (maxCapacity == 0) return 0;
        return (int) ((playerCount * 100.0) / maxCapacity);
    }

    /**
     * Set a custom property.
     */
    public void setProperty(String key, Object value) {
        customProperties.put(key, value);
    }

    /**
     * Get a custom property.
     */
    @SuppressWarnings("unchecked")
    public <T> T getProperty(String key, Class<T> type) {
        Object value = customProperties.get(key);
        if (type.isInstance(value)) {
            return (T) value;
        }
        return null;
    }

    /**
     * Get all custom properties.
     */
    public Map<String, Object> getProperties() {
        return new HashMap<>(customProperties);
    }

    // Getters and setters

    public int getPlayerCount() {
        return playerCount;
    }

    public void setPlayerCount(int playerCount) {
        this.playerCount = playerCount;
    }

    public int getMaxCapacity() {
        return maxCapacity;
    }

    public void setMaxCapacity(int maxCapacity) {
        this.maxCapacity = maxCapacity;
        // Recalculate soft cap
        this.softCap = (int) (maxCapacity * 0.8);
        this.hardCap = maxCapacity;
    }

    public double getTps() {
        return tps;
    }

    public void setTps(double tps) {
        this.tps = Math.min(tps, 20.0);
    }

    public ServiceStatus getStatus() {
        return status;
    }

    public void setStatus(ServiceStatus status) {
        this.status = status;
    }

    public long getLastHeartbeat() {
        return lastHeartbeat;
    }

    public void setLastHeartbeat(long lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }

    public int getSoftCap() {
        return softCap;
    }

    public void setSoftCap(int softCap) {
        this.softCap = softCap;
    }

    public int getHardCap() {
        return hardCap;
    }

    public void setHardCap(int hardCap) {
        this.hardCap = hardCap;
        this.maxCapacity = hardCap;
    }

    @Override
    public String toString() {
        return String.format("ServiceMetadata[players=%d/%d, tps=%.1f, status=%s, load=%d%%]",
                playerCount, maxCapacity, tps, status, getLoadPercentage());
    }
}