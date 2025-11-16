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
                                      FriendSnapshot actorSnapshot,
                                      FriendSnapshot targetSnapshot) {
        if (actor == null || target == null) {
            return false;
        }
        FriendSnapshot actorState = actorSnapshot != null ? actorSnapshot : FriendSnapshot.empty();
        FriendSnapshot targetState = targetSnapshot != null ? targetSnapshot : FriendSnapshot.empty();

        return !actorState.isBlocking(target) && !targetState.isBlocking(actor);
    }
}
