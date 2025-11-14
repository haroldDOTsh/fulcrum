package sh.harold.fulcrum.api.friends;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Asynchronous abstraction used by proxies and runtime services to access social graph state.
 */
public interface FriendService {

    CompletionStage<FriendSnapshot> getSnapshot(UUID playerId, boolean forceReload);

    CompletionStage<FriendOperationResult> execute(FriendMutationRequest request);

    default CompletionStage<FriendOperationResult> sendInvite(UUID actor, UUID target, Map<String, Object> metadata) {
        return execute(FriendMutationRequest.builder(FriendMutationType.INVITE_SEND)
                .actor(actor)
                .target(target)
                .metadata(metadata)
                .build());
    }

    default CompletionStage<FriendOperationResult> acceptInvite(UUID actor, UUID target) {
        return execute(FriendMutationRequest.builder(FriendMutationType.INVITE_ACCEPT)
                .actor(actor)
                .target(target)
                .build());
    }

    default CompletionStage<FriendOperationResult> declineInvite(UUID actor, UUID target) {
        return execute(FriendMutationRequest.builder(FriendMutationType.INVITE_DECLINE)
                .actor(actor)
                .target(target)
                .build());
    }

    default CompletionStage<FriendOperationResult> cancelInvite(UUID actor, UUID target) {
        return execute(FriendMutationRequest.builder(FriendMutationType.INVITE_CANCEL)
                .actor(actor)
                .target(target)
                .build());
    }

    default CompletionStage<FriendOperationResult> unfriend(UUID actor, UUID target) {
        return execute(FriendMutationRequest.builder(FriendMutationType.UNFRIEND)
                .actor(actor)
                .target(target)
                .build());
    }

    default CompletionStage<FriendOperationResult> block(UUID actor,
                                                         UUID target,
                                                         FriendBlockScope scope,
                                                         Instant expiresAt,
                                                         String reason) {
        return execute(FriendMutationRequest.builder(FriendMutationType.BLOCK)
                .actor(actor)
                .target(target)
                .scope(scope)
                .expiresAt(expiresAt)
                .reason(reason)
                .build());
    }

    default CompletionStage<FriendOperationResult> unblock(UUID actor,
                                                           UUID target,
                                                           FriendBlockScope scope) {
        return execute(FriendMutationRequest.builder(FriendMutationType.UNBLOCK)
                .actor(actor)
                .target(target)
                .scope(scope)
                .build());
    }
}
