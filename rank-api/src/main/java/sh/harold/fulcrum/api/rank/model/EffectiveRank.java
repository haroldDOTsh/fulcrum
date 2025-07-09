package sh.harold.fulcrum.api.rank.model;

import sh.harold.fulcrum.api.rank.enums.FunctionalRank;
import sh.harold.fulcrum.api.rank.enums.MonthlyPackageRank;
import sh.harold.fulcrum.api.rank.enums.PackageRank;
import sh.harold.fulcrum.api.rank.enums.RankPriority;

import java.util.Set;

/**
 * Represents the effective rank for a player after calculating priorities
 * between functional, package, and monthly ranks.
 */
public record EffectiveRank(
    FunctionalRank functional,
    PackageRank packageRank,
    MonthlyPackageRank monthly,
    RankPriority effectivePriority,
    String displayName,
    String colorCode,
    Set<String> permissions
) {
    
    /**
     * Check if the player has a specific permission.
     */
    public boolean hasPermission(String permission) {
        return permissions.contains(permission) || permissions.contains("*");
    }

    /**
     * Get the highest priority rank's display name.
     */
    public String getEffectiveDisplayName() {
        return switch (effectivePriority) {
            case MONTHLY_PACKAGE -> monthly != null ? monthly.getDisplayName() : displayName;
            case FUNCTIONAL -> functional != null ? functional.getDisplayName() : displayName;
            case PACKAGE -> packageRank.getDisplayName();
        };
    }

    /**
     * Get the highest priority rank's color code.
     */
    public String getEffectiveColorCode() {
        return switch (effectivePriority) {
            case MONTHLY_PACKAGE -> monthly != null ? monthly.getColorCode() : colorCode;
            case FUNCTIONAL -> functional != null ? functional.getColorCode() : colorCode;
            case PACKAGE -> packageRank.getColorCode();
        };
    }

    /**
     * Check if this player has a functional rank (staff member).
     */
    public boolean isStaff() {
        return functional != null;
    }

    /**
     * Check if this player has an active monthly rank.
     */
    public boolean hasMonthlyRank() {
        return monthly != null;
    }

    /**
     * Get the priority value for comparison.
     */
    public int getPriorityValue() {
        return effectivePriority.getValue();
    }
}