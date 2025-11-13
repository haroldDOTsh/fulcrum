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
 * Sent by the registry to a proxy to instruct it to route a player to a specific slot.
 */
@MessageType(value = "player.route.command", version = 1)
public class PlayerRouteCommand implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;
    private UUID requestId;
    private UUID playerId;
    private Action action = Action.ROUTE;
    private String playerName;
    private String proxyId;
    private String serverId;
    private String slotId;
    private String slotSuffix;
    private String targetWorld;
    private double spawnX;
    private double spawnY;
    private double spawnZ;
    private float spawnYaw;
    private float spawnPitch;
    private Map<String, String> metadata = new HashMap<>();
    public PlayerRouteCommand() {
        // for jackson
    }

    public UUID getRequestId() {
        return requestId;
    }

    public void setRequestId(UUID requestId) {
        this.requestId = requestId;
    }

    public Action getAction() {
        return action;
    }

    public void setAction(Action action) {
        this.action = action;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public void setPlayerId(UUID playerId) {
        this.playerId = playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public String getProxyId() {
        return proxyId;
    }

    public void setProxyId(String proxyId) {
        this.proxyId = proxyId;
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

    public String getTargetWorld() {
        return targetWorld;
    }

    public void setTargetWorld(String targetWorld) {
        this.targetWorld = targetWorld;
    }

    public double getSpawnX() {
        return spawnX;
    }

    public void setSpawnX(double spawnX) {
        this.spawnX = spawnX;
    }

    public double getSpawnY() {
        return spawnY;
    }

    public void setSpawnY(double spawnY) {
        this.spawnY = spawnY;
    }

    public double getSpawnZ() {
        return spawnZ;
    }

    public void setSpawnZ(double spawnZ) {
        this.spawnZ = spawnZ;
    }

    public float getSpawnYaw() {
        return spawnYaw;
    }

    public void setSpawnYaw(float spawnYaw) {
        this.spawnYaw = spawnYaw;
    }

    public float getSpawnPitch() {
        return spawnPitch;
    }

    public void setSpawnPitch(float spawnPitch) {
        this.spawnPitch = spawnPitch;
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
        if (requestId == null) {
            throw new IllegalStateException("requestId is required for PlayerRouteCommand");
        }
        if (playerId == null) {
            throw new IllegalStateException("playerId is required for PlayerRouteCommand");
        }
        if (playerName == null || playerName.isBlank()) {
            throw new IllegalStateException("playerName is required for PlayerRouteCommand");
        }
        if (proxyId == null || proxyId.isBlank()) {
            throw new IllegalStateException("proxyId is required for PlayerRouteCommand");
        }
        if (action == Action.ROUTE) {
            if (serverId == null || serverId.isBlank()) {
                throw new IllegalStateException("serverId is required for routing PlayerRouteCommand");
            }
            if (slotId == null || slotId.isBlank()) {
                throw new IllegalStateException("slotId is required for routing PlayerRouteCommand");
            }
        }
    }

    public enum Action {
        ROUTE,
        DISCONNECT
    }
}

