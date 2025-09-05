package sh.harold.fulcrum.api.messagebus.lifecycle;

/**
 * Enum representing the various states a service can be in.
 */
public enum ServiceStatus {
    /**
     * Service is starting up
     */
    STARTING("starting"),
    
    /**
     * Service is registering with the registry
     */
    REGISTERING("registering"),
    
    /**
     * Service is available and healthy
     */
    AVAILABLE("available"),
    
    /**
     * Service is running but not accepting new connections
     */
    FULL("full"),
    
    /**
     * Service is evacuating players/connections
     */
    EVACUATING("evacuating"),
    
    /**
     * Service is shutting down gracefully
     */
    STOPPING("stopping"),
    
    /**
     * Service has stopped or crashed
     */
    STOPPED("stopped"),
    
    /**
     * Service is not responding (timeout)
     */
    UNRESPONSIVE("unresponsive"),
    
    /**
     * Service is in maintenance mode
     */
    MAINTENANCE("maintenance");
    
    private final String statusName;
    
    ServiceStatus(String statusName) {
        this.statusName = statusName;
    }
    
    public String getStatusName() {
        return statusName;
    }
    
    /**
     * Check if the service is in a healthy state.
     */
    public boolean isHealthy() {
        return this == AVAILABLE || this == FULL;
    }
    
    /**
     * Check if the service can accept new connections.
     */
    public boolean canAcceptConnections() {
        return this == AVAILABLE;
    }
    
    /**
     * Check if the service is shutting down.
     */
    public boolean isShuttingDown() {
        return this == EVACUATING || this == STOPPING || this == STOPPED;
    }
    
    /**
     * Parse a status from a string.
     */
    public static ServiceStatus fromString(String value) {
        if (value == null) return AVAILABLE;
        
        String lower = value.toLowerCase();
        for (ServiceStatus status : values()) {
            if (status.statusName.equals(lower) || 
                status.name().equalsIgnoreCase(value)) {
                return status;
            }
        }
        
        // Special cases for backward compatibility
        if (lower.contains("dead")) return STOPPED;
        if (lower.contains("shutdown")) return STOPPING;
        
        return AVAILABLE;
    }
    
    @Override
    public String toString() {
        return statusName;
    }
}