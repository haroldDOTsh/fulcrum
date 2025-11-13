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
 * Broadcast by the registry when it needs to determine which proxy currently hosts a player.
 */
@MessageType(value = "player.locate.request", version = 1)
public class PlayerLocateRequest implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;

    private UUID requestId = UUID.randomUUID();
    private UUID playerId;
    private String playerName;
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
            throw new IllegalStateException("requestId is required for PlayerLocateRequest");
        }
        if ((playerId == null || playerId.version() == 0) && (playerName == null || playerName.isBlank())) {
            throw new IllegalStateException("playerId or playerName is required for PlayerLocateRequest");
        }
    }
}
