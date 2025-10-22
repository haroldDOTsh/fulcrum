package sh.harold.fulcrum.api.party;

/**
 * Shared constants for the party system.
 */
public final class PartyConstants {
    public static final int HARD_SIZE_CAP = 16;
    public static final long INVITE_TTL_SECONDS = 60L;
    public static final long DISCONNECT_GRACE_SECONDS = 300L;
    public static final long IDLE_DISBAND_GRACE_SECONDS = 300L;
    public static final long RESERVATION_TOKEN_TTL_SECONDS = 30L;
    private PartyConstants() {
    }
}
