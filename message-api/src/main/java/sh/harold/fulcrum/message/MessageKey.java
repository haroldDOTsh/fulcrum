package sh.harold.fulcrum.message;

/**
 * Legacy message key enum for backward compatibility.
 * New code should use the styled messaging system with string keys instead.
 * 
 * @deprecated Use Message.success(), Message.info(), etc. with string translation keys
 */
@Deprecated
public enum MessageKey {
    
    // Essential legacy keys for backward compatibility
    GENERIC_SUCCESS("success.generic", "Operation completed successfully."),
    GENERIC_ERROR("error.generic", "An error occurred."),
    NO_PERMISSION("error.no_permission", "You don't have permission to do that."),
    PLAYER_NOT_FOUND("error.player_not_found", "Player not found."),
    INVALID_ARGUMENT("error.invalid_argument", "Invalid argument."),
    
    // Plugin lifecycle
    PLUGIN_ENABLED("plugin.enabled", "Plugin has been enabled."),
    PLUGIN_DISABLED("plugin.disabled", "Plugin has been disabled."),
    PLUGIN_RELOADED("plugin.reloaded", "Plugin has been reloaded.");
    
    private final String key;
    private final String defaultMessage;
    
    MessageKey(String key, String defaultMessage) {
        this.key = key;
        this.defaultMessage = defaultMessage;
    }
    
    public String getKey() {
        return key;
    }
    
    public String getDefaultMessage() {
        return defaultMessage;
    }
    
    public static MessageKey fromKey(String key) {
        for (MessageKey messageKey : values()) {
            if (messageKey.key.equals(key)) {
                return messageKey;
            }
        }
        return null;
    }
}
