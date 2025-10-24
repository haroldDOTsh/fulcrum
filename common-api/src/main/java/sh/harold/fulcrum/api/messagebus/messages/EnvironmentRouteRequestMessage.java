package sh.harold.fulcrum.api.messagebus.messages;

import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.MessageType;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Direct environment routing request emitted by backend servers.
 */
@MessageType("environment.route.request")
public final class EnvironmentRouteRequestMessage implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;

    private UUID requestId = UUID.randomUUID();
    private UUID playerId;
    private String playerName;
    private String proxyId;
    private String originServerId;
    private String targetEnvironmentId;
    private String targetServerId;
    private String worldName;
    private double spawnX;
    private double spawnY;
    private double spawnZ;
    private float spawnYaw;
    private float spawnPitch;
    private FailureMode failureMode = FailureMode.KICK_ON_FAIL;
    private Map<String, String> metadata = new HashMap<>();

    public UUID getRequestId() {
        return requestId;
    }

    public void setRequestId(UUID requestId) {
        this.requestId = requestId;
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

    public String getOriginServerId() {
        return originServerId;
    }

    public void setOriginServerId(String originServerId) {
        this.originServerId = originServerId;
    }

    public String getTargetEnvironmentId() {
        return targetEnvironmentId;
    }

    public void setTargetEnvironmentId(String targetEnvironmentId) {
        this.targetEnvironmentId = targetEnvironmentId;
    }

    public String getTargetServerId() {
        return targetServerId;
    }

    public void setTargetServerId(String targetServerId) {
        this.targetServerId = targetServerId;
    }

    public String getWorldName() {
        return worldName;
    }

    public void setWorldName(String worldName) {
        this.worldName = worldName;
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

    public FailureMode getFailureMode() {
        return failureMode;
    }

    public void setFailureMode(FailureMode failureMode) {
        this.failureMode = failureMode;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
    }

    @Override
    public void validate() {
        if (requestId == null) {
            throw new IllegalStateException("requestId is required for EnvironmentRouteRequestMessage");
        }
        if (playerId == null) {
            throw new IllegalStateException("playerId is required for EnvironmentRouteRequestMessage");
        }
        if (playerName == null || playerName.isBlank()) {
            throw new IllegalStateException("playerName is required for EnvironmentRouteRequestMessage");
        }
        if (proxyId == null || proxyId.isBlank()) {
            throw new IllegalStateException("proxyId is required for EnvironmentRouteRequestMessage");
        }
        if (originServerId == null || originServerId.isBlank()) {
            throw new IllegalStateException("originServerId is required for EnvironmentRouteRequestMessage");
        }
        if (targetEnvironmentId == null || targetEnvironmentId.isBlank()) {
            throw new IllegalStateException("targetEnvironmentId is required for EnvironmentRouteRequestMessage");
        }
    }

    public enum FailureMode {
        KICK_ON_FAIL,
        REPORT_ONLY
    }
}

