package sh.harold.fulcrum.registry.social;

import sh.harold.fulcrum.api.friends.FriendBlockScope;
import sh.harold.fulcrum.api.friends.FriendMutationType;
import sh.harold.fulcrum.api.friends.FriendRelationState;
import sh.harold.fulcrum.api.friends.FriendSnapshot;

import java.time.Instant;
import java.util.UUID;

/**
 * Result of a transactional mutation applied to the social graph.
 */
public record FriendGraphMutationResult(
        boolean success,
        FriendMutationType mutationType,
        UUID actorId,
        UUID targetId,
        FriendRelationState actorState,
        FriendRelationState targetState,
        long actorRelationVersion,
        long targetRelationVersion,
        FriendSnapshot actorSnapshot,
        FriendSnapshot targetSnapshot,
        FriendBlockScope blockScope,
        boolean blockActive,
        Instant blockExpiresAt,
        Instant updatedAt,
        String error
) {
}
