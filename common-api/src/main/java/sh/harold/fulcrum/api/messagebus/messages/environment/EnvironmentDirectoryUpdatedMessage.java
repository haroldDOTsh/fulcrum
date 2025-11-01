package sh.harold.fulcrum.api.messagebus.messages.environment;

import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;

import java.io.Serializable;
import java.util.Objects;

/**
 * Broadcast emitted when the environment directory changes.
 */
public final class EnvironmentDirectoryUpdatedMessage implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;

    private String revision;

    public EnvironmentDirectoryUpdatedMessage() {
        // for Jackson
    }

    public EnvironmentDirectoryUpdatedMessage(String revision) {
        this.revision = Objects.requireNonNull(revision, "revision");
    }

    @Override
    public String getMessageType() {
        return ChannelConstants.REGISTRY_ENVIRONMENT_DIRECTORY_UPDATED;
    }

    public String getRevision() {
        return revision;
    }

    public void setRevision(String revision) {
        this.revision = revision;
    }
}
