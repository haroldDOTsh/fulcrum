package sh.harold.fulcrum.api.messagebus.messages;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.MessageType;

/**
 * Broadcast by backend servers to describe the current state of a logical slot.
 * The registry consumes these messages to build its availability model.
 */
@MessageType("slot.status.update")
public class SlotStatusUpdateMessage implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;

    private String serverId;
    private String slotId;
    private String slotSuffix;
    private String gameType;
    private SlotLifecycleStatus status = SlotLifecycleStatus.AVAILABLE;
    private int maxPlayers;
    private int onlinePlayers;
    private long timestamp = Instant.now().toEpochMilli();
    private Map<String, String> metadata = new HashMap<>();

    public SlotStatusUpdateMessage() {
        // for jackson
    }

    public SlotStatusUpdateMessage(String serverId, String slotId) {
        this.serverId = serverId;
        this.slotId = slotId;
    }

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public String getSlotId() {
        return slotId;
    }

    public void setSlotId(String slotId) {
        this.slotId = slotId;
    }

    public String getSlotSuffix() {
        return slotSuffix;
    }

    public void setSlotSuffix(String slotSuffix) {
        this.slotSuffix = slotSuffix;
    }

    public String getGameType() {
        return gameType;
    }

    public void setGameType(String gameType) {
        this.gameType = gameType;
    }

    public SlotLifecycleStatus getStatus() {
        return status;
    }

    public void setStatus(SlotLifecycleStatus status) {
        this.status = status;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public void setMaxPlayers(int maxPlayers) {
        this.maxPlayers = maxPlayers;
    }

    public int getOnlinePlayers() {
        return onlinePlayers;
    }

    public void setOnlinePlayers(int onlinePlayers) {
        this.onlinePlayers = onlinePlayers;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
    }

    public Map<String, String> getReadOnlyMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    @Override
    public void validate() {
        if (serverId == null || serverId.isBlank()) {
            throw new IllegalStateException("serverId is required for SlotStatusUpdateMessage");
        }
        if (slotId == null || slotId.isBlank()) {
            throw new IllegalStateException("slotId is required for SlotStatusUpdateMessage");
        }
        if (slotSuffix == null || slotSuffix.isBlank()) {
            throw new IllegalStateException("slotSuffix is required for SlotStatusUpdateMessage");
        }
        if (status == null) {
            throw new IllegalStateException("status is required for SlotStatusUpdateMessage");
        }
    }
}
