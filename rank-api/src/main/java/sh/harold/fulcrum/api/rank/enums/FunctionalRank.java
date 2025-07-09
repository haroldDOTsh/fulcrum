package sh.harold.fulcrum.api.rank.enums;

import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Functional ranks for internal staff and media roles.
 * These are optional ranks that provide administrative permissions.
 */
public enum FunctionalRank {
    ADMIN("ADMIN", "#FF0000", 100, "[ADMIN]", "[ADMIN] ", NamedTextColor.RED, NamedTextColor.RED),
    MODERATOR("MODERATOR", "#00FF00", 50, "[MOD]", "[MOD] ", NamedTextColor.GREEN, NamedTextColor.GREEN);

    private final String displayName;
    private final String colorCode;
    private final int priority;
    private final String rankPrefix;
    private final String tablistPrefix;
    private final NamedTextColor nameColor;
    private final NamedTextColor color;

    FunctionalRank(String displayName, String colorCode, int priority, String rankPrefix, 
                  String tablistPrefix, NamedTextColor nameColor, NamedTextColor color) {
        this.displayName = displayName;
        this.colorCode = colorCode;
        this.priority = priority;
        this.rankPrefix = rankPrefix;
        this.tablistPrefix = tablistPrefix;
        this.nameColor = nameColor;
        this.color = color;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getColorCode() {
        return colorCode;
    }

    public int getPriority() {
        return priority;
    }

    public String getRankPrefix() {
        return rankPrefix;
    }

    public String getTablistPrefix() {
        return tablistPrefix;
    }

    public NamedTextColor getNameColor() {
        return nameColor;
    }

    public NamedTextColor getColor() {
        return color;
    }

    /**
     * Check if this rank has higher or equal priority than another rank.
     */
    public boolean hasRankOrHigher(FunctionalRank other) {
        return this.priority >= other.priority;
    }
}