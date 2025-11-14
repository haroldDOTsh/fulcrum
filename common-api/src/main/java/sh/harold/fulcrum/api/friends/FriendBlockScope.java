package sh.harold.fulcrum.api.friends;

import java.util.Arrays;

/**
 * Enumerates the scopes a block may apply to. The ordinal values are persisted in PostgreSQL
 * so the mapping must remain stable.
 */
public enum FriendBlockScope {
    GLOBAL((short) 0),
    CHAT((short) 1),
    PARTY((short) 2),
    TRADE((short) 3);

    private final short code;

    FriendBlockScope(short code) {
        this.code = code;
    }

    public static FriendBlockScope fromCode(int code) {
        return Arrays.stream(values())
                .filter(scope -> scope.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown FriendBlockScope code: " + code));
    }

    public short code() {
        return code;
    }
}
