package sh.harold.fulcrum.api.messagebus.messages;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonIgnore;
import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.MessageType;

/**
 * Emitted by proxies when a player requests a slot in a minigame family.
 */
@MessageType("player.slot.request")
public class PlayerSlotRequest implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;

    private UUID requestId = UUID.randomUUID();
    private UUID playerId;
    private String playerName;
    private String proxyId;
    private String familyId;
    private Map<String, String> metadata = new HashMap<>();

    public PlayerSlotRequest() {
        // for jackson
    }

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

    public String getFamilyId() {
        return familyId;
    }

    public void setFamilyId(String familyId) {
        this.familyId = familyId;
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
            throw new IllegalStateException("requestId is required for PlayerSlotRequest");
        }
        if (playerId == null) {
            throw new IllegalStateException("playerId is required for PlayerSlotRequest");
        }
        if (playerName == null || playerName.isBlank()) {
            throw new IllegalStateException("playerName is required for PlayerSlotRequest");
        }
        if (proxyId == null || proxyId.isBlank()) {
            throw new IllegalStateException("proxyId is required for PlayerSlotRequest");
        }
        if (familyId == null || familyId.isBlank()) {
            throw new IllegalStateException("familyId is required for PlayerSlotRequest");
        }
    }
}
