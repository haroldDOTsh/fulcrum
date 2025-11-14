package sh.harold.fulcrum.fundamentals.chat.dm;

/**
 * Result states for direct message operations.
 */
public enum DirectMessageResult {
    DELIVERED,
    CHANNEL_OPENED,
    CHANNEL_CLOSED,
    TARGET_NOT_FOUND,
    RECIPIENT_OFFLINE,
    SELF_TARGET,
    MESSAGE_REQUIRED,
    RATE_LIMITED,
    REPLY_TARGET_MISSING,
    INTERNAL_ERROR
}
