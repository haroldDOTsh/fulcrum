package sh.harold.fulcrum.dialogue;

/**
 * Reason why a conversation session closed before natural completion.
 */
public enum DialogueCancelReason {
    PLAYER_LEFT,
    INTERRUPTED,
    REPLACED,
    DISTANCE,
    TIMEOUT,
    UNKNOWN
}
