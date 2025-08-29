package sh.harold.fulcrum.api.rank;

import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Unified rank enum containing all ranks in the system.
 * Supports permanent ranks, staff ranks, and subscription ranks with layering.
 */
public enum Rank {
    // Player ranks
    DEFAULT("Default", "&7", 0, "", "", NamedTextColor.GRAY, RankCategory.PLAYER),
    VIP("VIP", "&a", 10, "&a[VIP]", "&a[V]", NamedTextColor.GREEN, RankCategory.PLAYER),
    MVP("MVP", "&b", 20, "&b[MVP]", "&b[M]", NamedTextColor.AQUA, RankCategory.PLAYER),
    
    // Special ranks
    YOUTUBER("YouTuber", "&c", 15, "&c[YT]", "&c[YT]", NamedTextColor.RED, RankCategory.SPECIAL),
    
    // Subscription ranks
    MVP_PLUS("MVP+", "&b", 30, "&b[MVP&6+&b]", "&b[M&6+&b]", NamedTextColor.AQUA, RankCategory.SUBSCRIPTION),
    MVP_PLUS_PLUS("MVP++", "&6", 40, "&6[MVP&c++&6]", "&6[M&c++&6]", NamedTextColor.GOLD, RankCategory.SUBSCRIPTION),
    
    // Staff ranks
    HELPER("Helper", "&9", 100, "&9[HELPER]", "&9[H]", NamedTextColor.BLUE, RankCategory.STAFF),
    MODERATOR("Moderator", "&2", 200, "&2[MOD]", "&2[M]", NamedTextColor.DARK_GREEN, RankCategory.STAFF),
    ADMIN("Admin", "&4", 300, "&4[ADMIN]", "&4[A]", NamedTextColor.DARK_RED, RankCategory.STAFF);
    
    private final String displayName;
    private final String colorCode;
    private final int priority;
    private final String fullPrefix;
    private final String shortPrefix;
    private final NamedTextColor nameColor;
    private final RankCategory category;
    
    Rank(String displayName, String colorCode, int priority, String fullPrefix, 
         String shortPrefix, NamedTextColor nameColor, RankCategory category) {
        this.displayName = displayName;
        this.colorCode = colorCode;
        this.priority = priority;
        this.fullPrefix = fullPrefix;
        this.shortPrefix = shortPrefix;
        this.nameColor = nameColor;
        this.category = category;
    }
    
    /**
     * Gets the display name of the rank.
     */
    public String getDisplayName() {
        return displayName;
    }
    
    /**
     * Gets the color code for the rank (legacy format).
     */
    public String getColorCode() {
        return colorCode;
    }
    
    /**
     * Gets the priority of the rank. Higher values take precedence.
     */
    public int getPriority() {
        return priority;
    }
    
    /**
     * Gets the full prefix for the rank.
     */
    public String getFullPrefix() {
        return fullPrefix;
    }
    
    /**
     * Gets the short prefix for the rank.
     */
    public String getShortPrefix() {
        return shortPrefix;
    }
    
    /**
     * Gets the Adventure API name color for the rank.
     */
    public NamedTextColor getNameColor() {
        return nameColor;
    }
    
    /**
     * Gets the category of this rank.
     */
    public RankCategory getCategory() {
        return category;
    }
    
    /**
     * Checks if this rank is a staff rank.
     */
    public boolean isStaff() {
        return category == RankCategory.STAFF;
    }
    
    /**
     * Checks if this rank is a subscription rank.
     */
    public boolean isSubscription() {
        return category == RankCategory.SUBSCRIPTION;
    }
    
    /**
     * Compares ranks by priority.
     * Returns the rank with higher priority when multiple ranks exist.
     */
    public static Rank getHighestPriority(Rank... ranks) {
        if (ranks == null || ranks.length == 0) {
            return DEFAULT;
        }
        
        Rank highest = ranks[0];
        for (Rank rank : ranks) {
            if (rank != null && rank.getPriority() > highest.getPriority()) {
                highest = rank;
            }
        }
        return highest;
    }
}