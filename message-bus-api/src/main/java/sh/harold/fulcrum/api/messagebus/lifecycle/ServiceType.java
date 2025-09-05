package sh.harold.fulcrum.api.messagebus.lifecycle;

/**
 * Enum representing the different types of services in the Fulcrum ecosystem.
 */
public enum ServiceType {
    /**
     * Backend Minecraft server (game, lobby, etc.)
     */
    SERVER("server", "fulcrum-server"),
    
    /**
     * Velocity proxy server
     */
    PROXY("proxy", "fulcrum-proxy"),
    
    /**
     * Limbo service for holding players
     */
    LIMBO("limbo", "fulcrum-limbo"),
    
    /**
     * Central registry service
     */
    REGISTRY("registry", "fulcrum-registry"),
    
    /**
     * Unknown or custom service type
     */
    UNKNOWN("unknown", "fulcrum-service");
    
    private final String typeName;
    private final String idPrefix;
    
    ServiceType(String typeName, String idPrefix) {
        this.typeName = typeName;
        this.idPrefix = idPrefix;
    }
    
    /**
     * Get the type name used in messages.
     */
    public String getTypeName() {
        return typeName;
    }
    
    /**
     * Get the ID prefix for this service type.
     */
    public String getIdPrefix() {
        return idPrefix;
    }
    
    /**
     * Parse a service type from a string.
     * 
     * @param value The string value
     * @return The matching ServiceType or UNKNOWN
     */
    public static ServiceType fromString(String value) {
        if (value == null) return UNKNOWN;
        
        String lower = value.toLowerCase();
        for (ServiceType type : values()) {
            if (type.typeName.equals(lower) || 
                type.name().equalsIgnoreCase(value)) {
                return type;
            }
        }
        
        // Special cases for backward compatibility
        if (lower.contains("mini") || lower.contains("mega")) {
            return SERVER;
        }
        
        return UNKNOWN;
    }
    
    @Override
    public String toString() {
        return typeName;
    }
}