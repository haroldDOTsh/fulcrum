package sh.harold.fulcrum.api.friends;

import java.util.*;

/**
 * Immutable aggregate of a player's friend state used by proxies and runtimes for hot-path checks.
 */
public record FriendSnapshot(
        long version,
        Set<UUID> friends,
        Set<UUID> outgoingRequests,
        Set<UUID> incomingRequests,
        Map<FriendBlockScope, Set<UUID>> blockedOut,
        Map<FriendBlockScope, Set<UUID>> blockedIn
) {

    public FriendSnapshot {
        version = Math.max(0L, version);
        friends = friends == null ? Set.of() : Set.copyOf(friends);
        outgoingRequests = outgoingRequests == null ? Set.of() : Set.copyOf(outgoingRequests);
        incomingRequests = incomingRequests == null ? Set.of() : Set.copyOf(incomingRequests);
        blockedOut = sanitizeBlockMap(blockedOut);
        blockedIn = sanitizeBlockMap(blockedIn);
    }

    public static FriendSnapshot empty() {
        return new FriendSnapshot(0L, Set.of(), Set.of(), Set.of(), new EnumMap<>(FriendBlockScope.class), new EnumMap<>(FriendBlockScope.class));
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
        return blockedOut.getOrDefault(scope, Set.of());
    }

    public Set<UUID> blockedIn(FriendBlockScope scope) {
        return blockedIn.getOrDefault(scope, Set.of());
    }

    public boolean isEmpty() {
        return friends.isEmpty()
                && outgoingRequests.isEmpty()
                && incomingRequests.isEmpty()
                && blockedOut.values().stream().allMatch(Set::isEmpty)
                && blockedIn.values().stream().allMatch(Set::isEmpty);
    }
}
