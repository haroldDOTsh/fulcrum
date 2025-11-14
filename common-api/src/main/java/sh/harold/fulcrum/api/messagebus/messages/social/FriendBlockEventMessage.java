package sh.harold.fulcrum.api.messagebus.messages.social;

import sh.harold.fulcrum.api.friends.FriendBlockScope;
import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Broadcasted whenever a block row changes state.
 */
public final class FriendBlockEventMessage implements BaseMessage, Serializable {

    private static final long serialVersionUID = 1L;

    private UUID ownerId;
    private UUID targetId;
    private FriendBlockScope scope;
    private boolean active;
    private long ownerVersion;
    private long targetVersion;
    private Long expiresAtEpochMillis;
    private long updatedAtEpochMillis;

    @Override
    public String getMessageType() {
        return ChannelConstants.SOCIAL_FRIEND_BLOCKS;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
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

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public long getOwnerVersion() {
        return ownerVersion;
    }

    public void setOwnerVersion(long ownerVersion) {
        this.ownerVersion = ownerVersion;
    }

    public long getTargetVersion() {
        return targetVersion;
    }

    public void setTargetVersion(long targetVersion) {
        this.targetVersion = targetVersion;
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

    public long getUpdatedAtEpochMillis() {
        return updatedAtEpochMillis;
    }

    public void setUpdatedAtEpochMillis(long updatedAtEpochMillis) {
        this.updatedAtEpochMillis = updatedAtEpochMillis;
    }
}
