package sh.harold.fulcrum.api.friends;

import java.util.Objects;
import java.util.Optional;

/**
 * Result payload returned to requesters after a registry-side mutation.
 */
public record FriendOperationResult(
        boolean success,
        FriendMutationType mutationType,
        FriendSnapshot actorSnapshot,
        FriendSnapshot targetSnapshot,
        String error
) {

    public FriendOperationResult {
        Objects.requireNonNull(mutationType, "mutationType");
    }

    public static FriendOperationResult failure(FriendMutationType type, String error) {
        return new FriendOperationResult(false, type, null, null, error);
    }

    public static FriendOperationResult success(FriendMutationType type,
                                                FriendSnapshot actor,
                                                FriendSnapshot target) {
        return new FriendOperationResult(true, type, actor, target, null);
    }

    public Optional<String> errorMessage() {
        return Optional.ofNullable(error);
    }
}
