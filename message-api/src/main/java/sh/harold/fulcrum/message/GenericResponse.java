package sh.harold.fulcrum.message;

/**
 * Enum for common generic responses that can be used across the system.
 * These provide consistent error messages with standardized formatting.
 */
public enum GenericResponse {
    
    GENERIC_ERROR("error.generic", "An error occurred!"),
    NO_PERMISSION("error.no_permission", "You don't have permission to do that!"),
    PLAYER_NOT_FOUND("error.player_not_found", "Player not found!"),
    INVALID_ARGUMENT("error.invalid_argument", "Invalid argument!"),
    COMMAND_NOT_FOUND("error.command_not_found", "Command not found!"),
    INSUFFICIENT_FUNDS("error.insufficient_funds", "You don't have enough money!"),
    COOLDOWN_ACTIVE("error.cooldown_active", "You must wait before using this again!"),
    FEATURE_DISABLED("error.feature_disabled", "This feature is currently disabled!"),
    MAINTENANCE_MODE("error.maintenance_mode", "Server is in maintenance mode!"),
    RATE_LIMITED("error.rate_limited", "You're doing that too fast!"),
    OPERATION_FAILED("error.operation_failed", "Operation failed!"),
    INVALID_LOCATION("error.invalid_location", "Invalid location!"),
    ITEM_NOT_FOUND("error.item_not_found", "Item not found!"),
    INVENTORY_FULL("error.inventory_full", "Your inventory is full!"),
    WORLD_NOT_FOUND("error.world_not_found", "World not found!"),
    DATABASE_ERROR("error.database_error", "Database error occurred!");
    
    private final String key;
    private final String defaultMessage;
    
    GenericResponse(String key, String defaultMessage) {
        this.key = key;
        this.defaultMessage = defaultMessage;
    }
    
    public String getKey() {
        return key;
    }
    
    public String getDefaultMessage() {
        return defaultMessage;
    }
    
    /**
     * Finds a GenericResponse by its key.
     * 
     * @param key The key to search for
     * @return The matching GenericResponse, or null if not found
     */
    public static GenericResponse fromKey(String key) {
        for (GenericResponse response : values()) {
            if (response.key.equals(key)) {
                return response;
            }
        }
        return null;
    }
}
