package sh.harold.fulcrum.fundamentals.actionflag;

/**
 * Granular gameplay permissions controlled by the action flag system.
 * Each flag corresponds to a single bit in the effective mask.
 */
public enum ActionFlag {
    /**
     * Allows players to damage other players.
     */
    PVP,
    /**
     * Allows players to damage hostile mobs (monsters).
     */
    DAMAGE_HOSTILE,
    /**
     * Allows players to damage passive or neutral mobs.
     */
    DAMAGE_PASSIVE,
    /**
     * Allows players to break blocks.
     */
    BLOCK_BREAK,
    /**
     * Allows players to place blocks.
     */
    BLOCK_PLACE,
    /**
     * Allows players to modify the world through other mechanics (e.g., buckets, vehicles).
     */
    MODIFY_WORLD_OTHER,
    /**
     * Allows interaction with blocks (buttons, levers, doors, etc.).
     */
    INTERACT_BLOCK,
    /**
     * Allows interaction with entities (right-click use).
     */
    INTERACT_ENTITY,
    /**
     * Allows generic use actions (item use, consuming food, etc.).
     */
    GENERAL_USE,
    /**
     * Allows dropping items.
     */
    ITEM_DROP,
    /**
     * Allows picking up items.
     */
    ITEM_PICKUP;

    private final long mask;

    ActionFlag() {
        this.mask = 1L << this.ordinal();
    }

    public long mask() {
        return mask;
    }
}
