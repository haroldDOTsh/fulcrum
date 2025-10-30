package sh.harold.fulcrum.message.debug;

/**
 * Ordered debug visibility tiers. Higher tiers inherit the visibility of lower tiers.
 */
public enum DebugTier {
    NONE(0),
    PLAYER(1),
    COUNCIL(2),
    STAFF(3);

    private final int level;

    DebugTier(int level) {
        this.level = level;
    }

    public boolean allows(DebugTier required) {
        if (required == null) {
            return true;
        }
        return this.level >= required.level;
    }
}
