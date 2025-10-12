package sh.harold.fulcrum.velocity.api.rank;

/**
 * Unified rank enum for Velocity proxy.
 * Copy of the main Rank enum for use in Velocity context.
 */
public enum Rank {
    // Base player rank
    DEFAULT("Default", 0),

    // Donator ranks
    DONATOR_1("Donator I", 10),
    DONATOR_2("Donator II", 20),
    DONATOR_3("Donator III", 30),
    DONATOR_4("Donator IV", 40),

    // Staff ranks
    HELPER("Helper", 100),
    STAFF("Staff", 200);

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
        return this == STAFF;
    }
}
