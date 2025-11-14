package sh.harold.fulcrum.api.friends;

/**
 * High-level operation types so analytics and downstream listeners can respond consistently.
 */
public enum FriendMutationType {
    INVITE_SEND,
    INVITE_ACCEPT,
    INVITE_DECLINE,
    INVITE_CANCEL,
    UNFRIEND,
    BLOCK,
    UNBLOCK,
    SNAPSHOT_SYNC
}
