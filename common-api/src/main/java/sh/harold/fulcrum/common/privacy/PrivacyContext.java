package sh.harold.fulcrum.common.privacy;

import sh.harold.fulcrum.api.friends.FriendSnapshot;

import java.util.Objects;
import java.util.UUID;

public record PrivacyContext(
        UUID actorId,
        UUID targetId,
        boolean staff,
        FriendSnapshot actorSnapshot,
        FriendSnapshot targetSnapshot,
        boolean sharedServer,
        boolean sharedParty
) {

    public PrivacyContext {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(targetId, "targetId");
        actorSnapshot = actorSnapshot != null ? actorSnapshot : FriendSnapshot.empty();
        targetSnapshot = targetSnapshot != null ? targetSnapshot : FriendSnapshot.empty();
    }

    public boolean actorBlocksTarget() {
        return actorSnapshot.isBlocking(targetId);
    }

    public boolean targetBlocksActor() {
        return targetSnapshot.isBlocking(actorId);
    }

    public boolean eitherBlocks() {
        return actorBlocksTarget() || targetBlocksActor();
    }

    public boolean targetFriendsWithActor() {
        return targetSnapshot.friends().contains(actorId);
    }

    public boolean actorFriendsWithTarget() {
        return actorSnapshot.friends().contains(targetId);
    }

    public boolean mutualFriends() {
        return targetFriendsWithActor() && actorFriendsWithTarget();
    }
}
