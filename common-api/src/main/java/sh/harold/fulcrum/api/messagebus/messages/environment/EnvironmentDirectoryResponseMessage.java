package sh.harold.fulcrum.api.messagebus.messages.environment;

import sh.harold.fulcrum.api.environment.directory.EnvironmentDirectoryView;
import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Response payload carrying the environment directory snapshot.
 */
public final class EnvironmentDirectoryResponseMessage implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;

    private UUID requestId;
    private boolean success;
    private String error;
    private EnvironmentDirectoryView directory;

    public EnvironmentDirectoryResponseMessage() {
        // for Jackson
    }

    public EnvironmentDirectoryResponseMessage(UUID requestId,
                                               boolean success,
                                               String error,
                                               EnvironmentDirectoryView directory) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.success = success;
        this.error = error;
        this.directory = directory;
    }

    @Override
    public String getMessageType() {
        return ChannelConstants.REGISTRY_ENVIRONMENT_DIRECTORY_RESPONSE;
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

    public EnvironmentDirectoryView getDirectory() {
        return directory;
    }

    public void setDirectory(EnvironmentDirectoryView directory) {
        this.directory = directory;
    }
}
