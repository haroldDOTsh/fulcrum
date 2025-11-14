package sh.harold.fulcrum.api.messagebus.messages.social;

import sh.harold.fulcrum.api.friends.FriendMutationType;
import sh.harold.fulcrum.api.friends.FriendRelationState;
import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Broadcast payload describing mutations to friendship rows.
 */
public class FriendRelationEventMessage implements BaseMessage, Serializable {

    private static final long serialVersionUID = 1L;

    private UUID ownerId;
    private UUID peerId;
    private FriendRelationState ownerState;
    private FriendRelationState peerState;
    private FriendMutationType mutationType;
    private long ownerVersion;
    private long peerVersion;
    private long relationVersion;
    private Map<String, Object> metadata = new LinkedHashMap<>();
    private long updatedAtEpochMillis;

    public UUID getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
    }

    public UUID getPeerId() {
        return peerId;
    }

    public void setPeerId(UUID peerId) {
        this.peerId = peerId;
    }

    public FriendRelationState getOwnerState() {
        return ownerState;
    }

    public void setOwnerState(FriendRelationState ownerState) {
        this.ownerState = ownerState;
    }

    public FriendRelationState getPeerState() {
        return peerState;
    }

    public void setPeerState(FriendRelationState peerState) {
        this.peerState = peerState;
    }

    public FriendMutationType getMutationType() {
        return mutationType;
    }

    public void setMutationType(FriendMutationType mutationType) {
        this.mutationType = mutationType;
    }

    public long getOwnerVersion() {
        return ownerVersion;
    }

    public void setOwnerVersion(long ownerVersion) {
        this.ownerVersion = ownerVersion;
    }

    public long getPeerVersion() {
        return peerVersion;
    }

    public void setPeerVersion(long peerVersion) {
        this.peerVersion = peerVersion;
    }

    public long getRelationVersion() {
        return relationVersion;
    }

    public void setRelationVersion(long relationVersion) {
        this.relationVersion = relationVersion;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
    }

    public long getUpdatedAtEpochMillis() {
        return updatedAtEpochMillis;
    }

    public void setUpdatedAtEpochMillis(long updatedAtEpochMillis) {
        this.updatedAtEpochMillis = updatedAtEpochMillis;
    }

    @Override
    public String getMessageType() {
        return ChannelConstants.SOCIAL_FRIEND_UPDATES;
    }
}
