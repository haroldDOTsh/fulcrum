package sh.harold.fulcrum.api.friends;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.*;

/**
 * Immutable aggregate of a player's friend state used by proxies and runtimes for hot-path checks.
 */
public record FriendSnapshot(
        long version,
        Set<UUID> friends,
        Map<FriendBlockScope, Set<UUID>> ignoresOut,
        Map<FriendBlockScope, Set<UUID>> ignoresIn
) {

    public FriendSnapshot {
        version = Math.max(0L, version);
        friends = friends == null ? Set.of() : Set.copyOf(friends);
        ignoresOut = sanitizeBlockMap(ignoresOut);
        ignoresIn = sanitizeBlockMap(ignoresIn);
    }

    public static FriendSnapshot empty() {
        return new FriendSnapshot(0L, Set.of(), new EnumMap<>(FriendBlockScope.class), new EnumMap<>(FriendBlockScope.class));
    }

    private static Map<FriendBlockScope, Set<UUID>> sanitizeBlockMap(Map<FriendBlockScope, Set<UUID>> source) {
        EnumMap<FriendBlockScope, Set<UUID>> sanitized = new EnumMap<>(FriendBlockScope.class);
        if (source == null || source.isEmpty()) {
            FriendBlockScope[] values = FriendBlockScope.values();
            for (FriendBlockScope scope : values) {
                sanitized.put(scope, Set.of());
            }
            return Collections.unmodifiableMap(sanitized);
        }
        for (FriendBlockScope scope : FriendBlockScope.values()) {
            Set<UUID> entries = source.get(scope);
            sanitized.put(scope, entries == null ? Set.of() : Set.copyOf(entries));
        }
        return Collections.unmodifiableMap(sanitized);
    }

    public Set<UUID> blockedOut(FriendBlockScope scope) {
        return ignoresOut.getOrDefault(scope, Set.of());
    }

    public Set<UUID> blockedIn(FriendBlockScope scope) {
        return ignoresIn.getOrDefault(scope, Set.of());
    }

    @JsonIgnore
    public boolean isEmpty() {
        return friends.isEmpty()
                && ignoresOut.values().stream().allMatch(Set::isEmpty)
                && ignoresIn.values().stream().allMatch(Set::isEmpty);
    }
}
