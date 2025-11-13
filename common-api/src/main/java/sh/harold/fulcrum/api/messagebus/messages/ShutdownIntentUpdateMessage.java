package sh.harold.fulcrum.api.messagebus.messages;

import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageType;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Sent by services as they progress through the shutdown lifecycle.
 */
@MessageType(value = ChannelConstants.REGISTRY_SHUTDOWN_UPDATE, version = 1)
public final class ShutdownIntentUpdateMessage implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;
    private String intentId;
    private String serviceId;
    private Phase phase;
    private List<UUID> playerIds = new ArrayList<>();
    private long timestamp = Instant.now().toEpochMilli();
    public ShutdownIntentUpdateMessage() {
    }

    public String getIntentId() {
        return intentId;
    }

    public void setIntentId(String intentId) {
        this.intentId = intentId;
    }

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public Phase getPhase() {
        return phase;
    }

    public void setPhase(Phase phase) {
        this.phase = phase;
    }

    public List<UUID> getPlayerIds() {
        return Collections.unmodifiableList(playerIds);
    }

    public void setPlayerIds(List<UUID> playerIds) {
        this.playerIds = playerIds == null ? new ArrayList<>() : new ArrayList<>(playerIds);
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public void validate() {
        if (intentId == null || intentId.isBlank()) {
            throw new IllegalStateException("shutdown intent update missing intentId");
        }
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalStateException("shutdown intent update missing serviceId");
        }
        if (phase == null) {
            throw new IllegalStateException("shutdown intent update missing phase");
        }
        if (timestamp <= 0) {
            timestamp = Instant.now().toEpochMilli();
        }
    }

    @Override
    public String toString() {
        return "ShutdownIntentUpdateMessage{" +
                "intentId='" + intentId + '\'' +
                ", serviceId='" + serviceId + '\'' +
                ", phase=" + phase +
                ", playerIds=" + playerIds +
                ", timestamp=" + timestamp +
                '}';
    }

    public enum Phase {
        EVACUATE,
        EVICT,
        SHUTDOWN
    }
}
