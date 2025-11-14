package sh.harold.fulcrum.api.friends;

import java.util.Locale;
import java.util.UUID;

/**
 * Redis key helpers shared between the registry and runtime caches.
 */
public final class FriendRedisKeys {
    private FriendRedisKeys() {
    }

    private static String lowercaseUuid(UUID id) {
        return id.toString().toLowerCase(Locale.ROOT);
    }

    public static String snapshotKey(UUID playerId) {
        return "fulcrum:social:friends:snapshot:" + lowercaseUuid(playerId);
    }

    public static String friendSetKey(UUID playerId) {
        return "fulcrum:social:friends:set:" + lowercaseUuid(playerId);
    }

    public static String outgoingKey(UUID playerId) {
        return "fulcrum:social:friends:outgoing:" + lowercaseUuid(playerId);
    }

    public static String incomingKey(UUID playerId) {
        return "fulcrum:social:friends:incoming:" + lowercaseUuid(playerId);
    }

    public static String blockedOutKey(UUID playerId, FriendBlockScope scope) {
        return "fulcrum:social:friends:blocked-out:" + scope.name().toLowerCase(Locale.ROOT) + ':' + lowercaseUuid(playerId);
    }

    public static String blockedInKey(UUID playerId, FriendBlockScope scope) {
        return "fulcrum:social:friends:blocked-in:" + scope.name().toLowerCase(Locale.ROOT) + ':' + lowercaseUuid(playerId);
    }
}
