package sh.harold.fulcrum.api.party;

import java.util.UUID;

/**
 * Shared Redis key utilities for the party system.
 */
public final class PartyRedisKeys {
    private static final String BASE = "fulcrum:party:";
    private static final String DATA = BASE + "data:";
    private static final String MEMBER = BASE + "member:";
    private static final String INVITE = BASE + "invite:";
    private static final String RESERVATION = BASE + "reservation:";
    private static final String ACTIVE = BASE + "active";

    private PartyRedisKeys() {
    }

    public static String partyDataKey(UUID partyId) {
        return DATA + partyId;
    }

    public static String partyMembersLookupKey(UUID playerId) {
        return MEMBER + playerId;
    }

    public static String partyInviteKey(UUID targetPlayerId) {
        return INVITE + targetPlayerId;
    }

    public static String partyReservationKey(String reservationId) {
        return RESERVATION + reservationId;
    }

    public static String activePartiesSet() {
        return ACTIVE;
    }
}
