package sh.harold.fulcrum.api.rank.enums;

import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Monthly package ranks for time-limited subscription ranks.
 * These are premium ranks that expire after a certain period.
 */
public enum MonthlyPackageRank {
    MVP_PLUS("MVP+", "#FFD700", 30, "[MVP+]", "[MVP+] ", NamedTextColor.GOLD, NamedTextColor.GOLD),
    MVP_PLUS_PLUS("MVP++", "#FF6600", 40, "[MVP++]", "[MVP++] ", NamedTextColor.DARK_RED, NamedTextColor.DARK_RED);

    private final String displayName;
    private final String colorCode;
    private final int priority;
    private final String rankPrefix;
    private final String tablistPrefix;
    private final NamedTextColor nameColor;
    private final NamedTextColor color;

    MonthlyPackageRank(String displayName, String colorCode, int priority, String rankPrefix, 
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
    public boolean hasRankOrHigher(MonthlyPackageRank other) {
        return this.priority >= other.priority;
    }
}