package sh.harold.fulcrum.velocity.api.rank;

/**
 * Unified rank enum for Velocity proxy.
 * Copy of the main Rank enum for use in Velocity context.
 */
public enum Rank {
    // Player ranks
    DEFAULT("Default", 0),
    VIP("VIP", 10),
    MVP("MVP", 20),
    
    // Special ranks
    YOUTUBER("YouTuber", 15),
    
    // Subscription ranks
    MVP_PLUS("MVP+", 30),
    MVP_PLUS_PLUS("MVP++", 40),
    
    // Staff ranks
    HELPER("Helper", 100),
    MODERATOR("Moderator", 200),
    ADMIN("Admin", 300);
    
    private final String displayName;
    private final int priority;
    
    Rank(String displayName, int priority) {
        this.displayName = displayName;
        this.priority = priority;
    }
    
    /**
     * Gets the display name of the rank.
     */
    public String getDisplayName() {
        return displayName;
    }
    
    /**
     * Gets the priority of the rank. Higher values take precedence.
     */
    public int getPriority() {
        return priority;
    }
    
    /**
     * Checks if this rank is a staff rank.
     */
    public boolean isStaff() {
        return priority >= HELPER.priority;
    }
    
    /**
     * Checks if this rank is admin.
     */
    public boolean isAdmin() {
        return this == ADMIN;
    }
}