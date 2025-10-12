package sh.harold.fulcrum.registry.slot;

import sh.harold.fulcrum.api.messagebus.messages.SlotLifecycleStatus;
import sh.harold.fulcrum.api.messagebus.messages.SlotStatusUpdateMessage;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime representation of a logical slot hosted on a registered server.
 */
public class LogicalSlotRecord {
    private final String slotId;
    private final String slotSuffix;
    private final String serverId;
    private final Map<String, String> metadata = new ConcurrentHashMap<>();
    private volatile String gameType;
    private volatile SlotLifecycleStatus status;
    private volatile int maxPlayers;
    private volatile int onlinePlayers;
    private volatile long lastUpdated;

    public LogicalSlotRecord(String slotId, String slotSuffix, String serverId) {
        this.slotId = slotId;
        this.slotSuffix = slotSuffix;
        this.serverId = serverId;
        this.status = SlotLifecycleStatus.PROVISIONING;
        this.lastUpdated = System.currentTimeMillis();
    }

    public String getSlotId() {
        return slotId;
    }

    public String getSlotSuffix() {
        return slotSuffix;
    }

    public String getServerId() {
        return serverId;
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

    public long getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(long lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public Map<String, String> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    public void replaceMetadata(Map<String, String> updated) {
        metadata.clear();
        if (updated != null) {
            metadata.putAll(updated);
        }
    }

    public void applyUpdate(SlotStatusUpdateMessage update) {
        setGameType(update.getGameType());
        setStatus(update.getStatus());
        setMaxPlayers(update.getMaxPlayers());
        setOnlinePlayers(update.getOnlinePlayers());
        setLastUpdated(System.currentTimeMillis());
        replaceMetadata(update.getReadOnlyMetadata());
    }

    @Override
    public String toString() {
        return String.format("LogicalSlotRecord[id=%s, status=%s, players=%d/%d]",
                slotId, status, onlinePlayers, maxPlayers);
    }
}
