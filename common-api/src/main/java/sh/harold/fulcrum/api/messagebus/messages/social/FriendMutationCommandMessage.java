package sh.harold.fulcrum.api.messagebus.messages.social;

import sh.harold.fulcrum.api.friends.FriendBlockScope;
import sh.harold.fulcrum.api.friends.FriendMutationType;
import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;

import java.io.Serializable;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Directed friend mutation request sent from proxies to the registry service.
 */
public final class FriendMutationCommandMessage implements BaseMessage, Serializable {

    private static final long serialVersionUID = 1L;

    private UUID requestId;
    private FriendMutationType mutationType;
    private UUID actorId;
    private UUID targetId;
    private FriendBlockScope scope;
    private Long expiresAtEpochMillis;
    private String reason;
    private Map<String, Object> metadata = new LinkedHashMap<>();
    private String actorName;
    private String targetName;

    public FriendMutationCommandMessage() {
    }

    public FriendMutationCommandMessage(UUID requestId,
                                        FriendMutationType mutationType,
                                        UUID actorId,
                                        UUID targetId) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.mutationType = Objects.requireNonNull(mutationType, "mutationType");
        this.actorId = Objects.requireNonNull(actorId, "actorId");
        this.targetId = Objects.requireNonNull(targetId, "targetId");
    }

    @Override
    public String getMessageType() {
        return ChannelConstants.SOCIAL_FRIEND_MUTATION_REQUEST;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public void setRequestId(UUID requestId) {
        this.requestId = requestId;
    }

    public FriendMutationType getMutationType() {
        return mutationType;
    }

    public void setMutationType(FriendMutationType mutationType) {
        this.mutationType = mutationType;
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

    public FriendBlockScope getScope() {
        return scope;
    }

    public void setScope(FriendBlockScope scope) {
        this.scope = scope;
    }

    public Long getExpiresAtEpochMillis() {
        return expiresAtEpochMillis;
    }

    public void setExpiresAtEpochMillis(Long expiresAtEpochMillis) {
        this.expiresAtEpochMillis = expiresAtEpochMillis;
    }

    public Instant resolveExpiresAt() {
        return expiresAtEpochMillis == null ? null : Instant.ofEpochMilli(expiresAtEpochMillis);
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
    }

    public String getActorName() {
        return actorName;
    }

    public void setActorName(String actorName) {
        this.actorName = actorName;
    }

    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(String targetName) {
        this.targetName = targetName;
    }
}
