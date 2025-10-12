package sh.harold.fulcrum.api.messagebus.messages;

/**
 * High-level lifecycle states for logical server slots.
 * These align with the slot orchestration terminology used across services.
 */
public enum SlotLifecycleStatus {
    /**
     * Slot is being prepared and not ready for players.
     */
    PROVISIONING,
    /**
     * Slot can accept players immediately.
     */
    AVAILABLE,
    /**
     * Slot is reserved for an incoming roster.
     */
    ALLOCATED,
    /**
     * Slot is actively running gameplay.
     */
    IN_GAME,
    /**
     * Slot is cooling down or resetting resources.
     */
    COOLDOWN,
    /**
     * Slot experienced a failure and requires intervention.
     */
    FAULTED
}
