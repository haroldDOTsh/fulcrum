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

    public static String ignoresOutKey(UUID playerId, FriendBlockScope scope) {
        return "fulcrum:social:friends:ignores-out:" + scope.name().toLowerCase(Locale.ROOT) + ':' + lowercaseUuid(playerId);
    }

    public static String ignoresInKey(UUID playerId, FriendBlockScope scope) {
        return "fulcrum:social:friends:ignores-in:" + scope.name().toLowerCase(Locale.ROOT) + ':' + lowercaseUuid(playerId);
    }

    public static String pendingInvitesKey(UUID targetId) {
        return "fulcrum:social:friends:pending:" + lowercaseUuid(targetId);
    }

    public static String inviteKey(UUID actorId, UUID targetId) {
        return "fulcrum:social:friends:invite:" + lowercaseUuid(actorId) + ':' + lowercaseUuid(targetId);
    }

    public static String blockExpiryKey() {
        return "fulcrum:social:friends:block-expiries";
    }
}
