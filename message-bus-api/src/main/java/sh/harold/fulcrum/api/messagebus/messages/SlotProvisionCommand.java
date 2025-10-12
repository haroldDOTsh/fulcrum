package sh.harold.fulcrum.api.messagebus.messages;

import com.fasterxml.jackson.annotation.JsonIgnore;
import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.MessageType;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Command issued by the registry requesting that a backend provision a logical slot for a family.
 */
@MessageType("slot.provision.command")
public class SlotProvisionCommand implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;

    private UUID requestId = UUID.randomUUID();
    private String serverId;
    private String family;
    private String variant;
    private Map<String, String> metadata = new HashMap<>();

    public SlotProvisionCommand() {
        // jackson
    }

    public SlotProvisionCommand(String serverId, String family) {
        this.serverId = serverId;
        this.family = family;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public void setRequestId(UUID requestId) {
        this.requestId = requestId;
    }

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public String getFamily() {
        return family;
    }

    public void setFamily(String family) {
        this.family = family;
    }

    public String getVariant() {
        return variant;
    }

    public void setVariant(String variant) {
        this.variant = variant;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
    }

    @JsonIgnore
    public Map<String, String> getReadOnlyMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    @Override
    public void validate() {
        if (serverId == null || serverId.isBlank()) {
            throw new IllegalStateException("serverId is required for SlotProvisionCommand");
        }
        if (family == null || family.isBlank()) {
            throw new IllegalStateException("family is required for SlotProvisionCommand");
        }
    }
}
