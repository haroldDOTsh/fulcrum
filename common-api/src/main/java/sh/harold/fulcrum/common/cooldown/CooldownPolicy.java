package sh.harold.fulcrum.common.cooldown;

/**
 * Determines how the registry treats attempts to acquire a cooldown
 * that is already active.
 */
public enum CooldownPolicy {
    /**
     * Reject the acquisition while the cooldown is still active.
     */
    REJECT_WHILE_ACTIVE,

    /**
     * Always accept the acquisition and reset the expiry window starting now.
     */
    EXTEND_ON_ACQUIRE
}
