package sh.harold.fulcrum.api.messagebus.messages.environment;

import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Request message for retrieving the environment directory snapshot from the registry.
 */
public final class EnvironmentDirectoryRequestMessage implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;

    private UUID requestId;
    private boolean refresh;

    public EnvironmentDirectoryRequestMessage() {
        // for Jackson
    }

    public EnvironmentDirectoryRequestMessage(UUID requestId, boolean refresh) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.refresh = refresh;
    }

    @Override
    public String getMessageType() {
        return ChannelConstants.REGISTRY_ENVIRONMENT_DIRECTORY_REQUEST;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public void setRequestId(UUID requestId) {
        this.requestId = requestId;
    }

    public boolean isRefresh() {
        return refresh;
    }

    public void setRefresh(boolean refresh) {
        this.refresh = refresh;
    }
}
