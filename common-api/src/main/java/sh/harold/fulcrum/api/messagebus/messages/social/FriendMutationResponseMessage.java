package sh.harold.fulcrum.api.messagebus.messages.social;

import sh.harold.fulcrum.api.friends.FriendMutationType;
import sh.harold.fulcrum.api.friends.FriendSnapshot;
import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;

import java.io.Serializable;
import java.util.UUID;

/**
 * Response emitted by the registry after processing a mutation request.
 */
public final class FriendMutationResponseMessage implements BaseMessage, Serializable {

    private static final long serialVersionUID = 1L;

    private UUID requestId;
    private boolean success;
    private String error;
    private FriendMutationType mutationType;
    private UUID actorId;
    private UUID targetId;
    private FriendSnapshot actorSnapshot;
    private FriendSnapshot targetSnapshot;

    public FriendMutationResponseMessage() {
    }

    public FriendMutationResponseMessage(UUID requestId,
                                         boolean success,
                                         FriendMutationType mutationType,
                                         UUID actorId,
                                         UUID targetId,
                                         FriendSnapshot actorSnapshot,
                                         FriendSnapshot targetSnapshot,
                                         String error) {
        this.requestId = requestId;
        this.success = success;
        this.mutationType = mutationType;
        this.actorId = actorId;
        this.targetId = targetId;
        this.actorSnapshot = actorSnapshot;
        this.targetSnapshot = targetSnapshot;
        this.error = error;
    }

    @Override
    public String getMessageType() {
        return ChannelConstants.SOCIAL_FRIEND_MUTATION_RESPONSE;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public void setRequestId(UUID requestId) {
        this.requestId = requestId;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public FriendMutationType getMutationType() {
        return mutationType;
    }

    public void setMutationType(FriendMutationType mutationType) {
        this.mutationType = mutationType;
    }

    public FriendSnapshot getActorSnapshot() {
        return actorSnapshot;
    }

    public void setActorSnapshot(FriendSnapshot actorSnapshot) {
        this.actorSnapshot = actorSnapshot;
    }

    public FriendSnapshot getTargetSnapshot() {
        return targetSnapshot;
    }

    public void setTargetSnapshot(FriendSnapshot targetSnapshot) {
        this.targetSnapshot = targetSnapshot;
    }

    public UUID getActorId() {
        return actorId;
    }

    public void setActorId(UUID actorId) {
        this.actorId = actorId;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public void setTargetId(UUID targetId) {
        this.targetId = targetId;
    }
}
