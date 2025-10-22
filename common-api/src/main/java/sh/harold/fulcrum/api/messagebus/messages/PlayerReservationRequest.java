package sh.harold.fulcrum.api.messagebus.messages;

import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;

import java.io.Serializable;
import java.util.Map;
import java.util.UUID;

/**
 * Request message sent from the registry to a backend server asking it to
 * reserve capacity for an incoming player transfer.
 */
public class PlayerReservationRequest implements BaseMessage, Serializable {

    private UUID requestId;
    private UUID playerId;
    private String playerName;
    private String proxyId;
    private String serverId;
    private String slotId;
    private Map<String, String> metadata;

    @Override
    public String getMessageType() {
        return ChannelConstants.PLAYER_RESERVATION_REQUEST;
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

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }

    @Override
    public void validate() {
        if (requestId == null) {
            throw new IllegalStateException("requestId is required for PlayerReservationRequest");
        }
        if (playerId == null) {
            throw new IllegalStateException("playerId is required for PlayerReservationRequest");
        }
        if (proxyId == null || proxyId.isBlank()) {
            throw new IllegalStateException("proxyId is required for PlayerReservationRequest");
        }
        if (serverId == null || serverId.isBlank()) {
            throw new IllegalStateException("serverId is required for PlayerReservationRequest");
        }
        if (slotId == null || slotId.isBlank()) {
            throw new IllegalStateException("slotId is required for PlayerReservationRequest");
        }
    }
}
