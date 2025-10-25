package sh.harold.fulcrum.api.messagebus.messages.network;

import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Request targeted at the registry for the current network profile.
 */
public final class NetworkConfigRequestMessage implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;

    private UUID requestId;
    private String profileId;
    private boolean refresh;

    public NetworkConfigRequestMessage() {
        // for Jackson
    }

    public NetworkConfigRequestMessage(UUID requestId, String profileId, boolean refresh) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.profileId = profileId;
        this.refresh = refresh;
    }

    @Override
    public String getMessageType() {
        return ChannelConstants.REGISTRY_NETWORK_CONFIG_REQUEST;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public void setRequestId(UUID requestId) {
        this.requestId = requestId;
    }

    public String getProfileId() {
        return profileId;
    }

    public void setProfileId(String profileId) {
        this.profileId = profileId;
    }

    public boolean isRefresh() {
        return refresh;
    }

    public void setRefresh(boolean refresh) {
        this.refresh = refresh;
    }
}
