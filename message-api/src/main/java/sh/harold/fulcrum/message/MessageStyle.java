package sh.harold.fulcrum.message;

/**
 * Represents different message styles with their associated colors and formatting.
 * Each style defines a base color and argument color for consistent messaging.
 */
public enum MessageStyle {
    
    SUCCESS("&a", "&e"),    // Green base, yellow arguments
    INFO("&7", "&b"),       // Gray base, aqua arguments  
    DEBUG("&8", "&8"),      // Dark gray base, dark gray arguments
    ERROR("&c", "&c"),      // Red base, red arguments
    RAW(null, null);        // No default colors, uses translation file colors
    
    private final String baseColor;
    private final String argumentColor;
    
    MessageStyle(String baseColor, String argumentColor) {
        this.baseColor = baseColor;
        this.argumentColor = argumentColor;
    }
    
    /**
     * Gets the base color for this message style.
     * 
     * @return The base color code, or null for RAW style
     */
    public String getBaseColor() {
        return baseColor;
    }
    
    /**
     * Gets the argument color for this message style.
     * 
     * @return The argument color code, or null for RAW style
     */
    public String getArgumentColor() {
        return argumentColor;
    }
    
    /**
     * Checks if this style has predefined colors.
     * 
     * @return True if the style has predefined colors, false for RAW
     */
    public boolean hasColors() {
        return baseColor != null && argumentColor != null;
    }
}
