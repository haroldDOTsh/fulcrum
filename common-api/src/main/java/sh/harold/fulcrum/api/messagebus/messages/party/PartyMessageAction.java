package sh.harold.fulcrum.api.messagebus.messages.party;

/**
 * High-level events that can occur to a party.
 */
public enum PartyMessageAction {
    CREATED,
    UPDATED,
    MEMBER_JOINED,
    MEMBER_LEFT,
    MEMBER_KICKED,
    ROLE_CHANGED,
    INVITE_SENT,
    INVITE_ACCEPTED,
    INVITE_EXPIRED,
    INVITE_REVOKED,
    TRANSFERRED,
    DISBANDED,
    SETTINGS_UPDATED,
    WARP_REQUESTED,
    WARP_COMPLETED,
    WARP_FAILED,
    RESERVATION_CREATED,
    RESERVATION_CLAIMED
}
