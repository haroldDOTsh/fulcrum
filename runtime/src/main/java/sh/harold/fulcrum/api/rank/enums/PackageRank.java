package sh.harold.fulcrum.api.rank.enums;

import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Package ranks for permanent purchased ranks.
 * These provide the baseline rank system with DEFAULT being the minimum.
 */
public enum PackageRank {
    DEFAULT("Default", "#FFFFFF", 0, "", "", NamedTextColor.WHITE, NamedTextColor.WHITE),
    VIP("VIP", "#00FF00", 10, "[VIP]", "[VIP] ", NamedTextColor.GREEN, NamedTextColor.GREEN),
    MVP("MVP", "#FFD700", 20, "[MVP]", "[MVP] ", NamedTextColor.GOLD, NamedTextColor.GOLD),
    YOUTUBER("YouTuber", "#FF0000", 15, "[YT]", "[YT] ", NamedTextColor.RED, NamedTextColor.RED);

    private final String displayName;
    private final String colorCode;
    private final int priority;
    private final String rankPrefix;
    private final String tablistPrefix;
    private final NamedTextColor nameColor;
    private final NamedTextColor color;

    PackageRank(String displayName, String colorCode, int priority, String rankPrefix, 
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
    public boolean hasRankOrHigher(PackageRank other) {
        return this.priority >= other.priority;
    }

    /**
     * Get the default rank for new players.
     */
    public static PackageRank getDefault() {
        return DEFAULT;
    }
}