package sh.harold.fulcrum.api.messagebus.messages.network;

import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Broadcast when a network profile is promoted to active.
 */
public final class NetworkConfigUpdatedMessage implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;

    private String profileId;
    private String tag;
    private Instant updatedAt;

    public NetworkConfigUpdatedMessage() {
        // for Jackson
    }

    public NetworkConfigUpdatedMessage(String profileId, String tag, Instant updatedAt) {
        this.profileId = Objects.requireNonNull(profileId, "profileId");
        this.tag = Objects.requireNonNull(tag, "tag");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    @Override
    public String getMessageType() {
        return ChannelConstants.REGISTRY_NETWORK_CONFIG_UPDATED;
    }

    public String getProfileId() {
        return profileId;
    }

    public void setProfileId(String profileId) {
        this.profileId = profileId;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
