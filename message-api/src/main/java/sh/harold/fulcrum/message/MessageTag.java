package sh.harold.fulcrum.message;

/**
 * Predefined message tags that can be added to messages.
 * These tags appear as prefixes before the main message content.
 */
public enum MessageTag {
    
    STAFF("&c[STAFF]&r", "Staff-only message indicator"),
    DAEMON("&5[DAEMON]&r", "Daemon/system process indicator"),
    DEBUG("&8[DEBUG]&r", "Debug information indicator"),
    SYSTEM("&b[SYSTEM]&r", "System message indicator");
    
    private final String prefix;
    private final String description;
    
    MessageTag(String prefix, String description) {
        this.prefix = prefix;
        this.description = description;
    }
    
    /**
     * Gets the colored prefix for this tag.
     * 
     * @return The prefix with color codes
     */
    public String getPrefix() {
        return prefix;
    }
    
    /**
     * Gets the description of this tag.
     * 
     * @return The tag description
     */
    public String getDescription() {
        return description;
    }
    
    /**
     * Gets the prefix with a trailing space for message formatting.
     * 
     * @return The prefix with a space
     */
    public String getPrefixWithSpace() {
        return prefix + " ";
    }
}
