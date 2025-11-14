package sh.harold.fulcrum.api.friends;

import java.util.UUID;

/**
 * Shared helper for consistent interaction checks across chat, parties, etc.
 */
public final class FriendInteractions {
    private FriendInteractions() {
    }

    public static boolean canInteract(UUID actor,
                                      UUID target,
                                      FriendBlockScope scope,
                                      FriendSnapshot actorSnapshot,
                                      FriendSnapshot targetSnapshot) {
        if (actor == null || target == null || scope == null) {
            return false;
        }
        FriendSnapshot actorState = actorSnapshot != null ? actorSnapshot : FriendSnapshot.empty();
        FriendSnapshot targetState = targetSnapshot != null ? targetSnapshot : FriendSnapshot.empty();

        if (hasBlock(actorState, target, scope) || hasBlock(actorState, target, FriendBlockScope.GLOBAL)) {
            return false;
        }
        return !hasBlock(targetState, actor, scope) && !hasBlock(targetState, actor, FriendBlockScope.GLOBAL);
    }

    private static boolean hasBlock(FriendSnapshot snapshot, UUID peer, FriendBlockScope scope) {
        return snapshot.blockedOut(scope).contains(peer);
    }
}
