package sh.harold.fulcrum.api.messagebus.messages.network;

import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.network.NetworkProfileView;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Response from the registry containing a network profile snapshot.
 */
public final class NetworkConfigResponseMessage implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;

    private UUID requestId;
    private boolean success;
    private String error;
    private NetworkProfileView profile;

    public NetworkConfigResponseMessage() {
        // for Jackson
    }

    public NetworkConfigResponseMessage(UUID requestId,
                                        boolean success,
                                        String error,
                                        NetworkProfileView profile) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.success = success;
        this.error = error;
        this.profile = profile;
    }

    @Override
    public String getMessageType() {
        return ChannelConstants.REGISTRY_NETWORK_CONFIG_RESPONSE;
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

    public NetworkProfileView getProfile() {
        return profile;
    }

    public void setProfile(NetworkProfileView profile) {
        this.profile = profile;
    }
}
