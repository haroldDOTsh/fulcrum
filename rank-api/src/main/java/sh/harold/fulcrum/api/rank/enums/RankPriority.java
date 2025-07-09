package sh.harold.fulcrum.api.rank.enums;

/**
 * Defines the priority hierarchy for different rank types.
 * Higher values indicate higher priority.
 */
public enum RankPriority {
    PACKAGE(10),
    MONTHLY_PACKAGE(25),
    FUNCTIONAL(50); 

    private final int value;

    RankPriority(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    /**
     * Compare priorities - higher values take precedence.
     */
    public boolean isHigherThan(RankPriority other) {
        return this.value > other.value;
    }

    /**
     * Get the priority for the highest active rank type.
     */
    public static RankPriority getEffectivePriority(boolean hasMonthly, boolean hasFunctional) {
        if (hasMonthly) return MONTHLY_PACKAGE;
        if (hasFunctional) return FUNCTIONAL;
        return PACKAGE;
    }
}