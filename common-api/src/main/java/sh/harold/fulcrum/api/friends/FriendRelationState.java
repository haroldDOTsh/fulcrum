package sh.harold.fulcrum.api.friends;

import java.util.Arrays;

/**
 * Directed relation state between two players. The numeric codes are stored in SQL records
 * and must not be reordered.
 */
public enum FriendRelationState {
    INVITE_OUTGOING((short) 0),
    INVITE_INCOMING((short) 1),
    ACCEPTED((short) 2);

    private final short code;

    FriendRelationState(short code) {
        this.code = code;
    }

    public static FriendRelationState fromCode(int code) {
        return Arrays.stream(values())
                .filter(state -> state.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown FriendRelationState code: " + code));
    }

    public short code() {
        return code;
    }
}
