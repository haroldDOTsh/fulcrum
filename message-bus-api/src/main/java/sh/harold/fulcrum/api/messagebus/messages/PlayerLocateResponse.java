package sh.harold.fulcrum.api.messagebus.messages;

import java.io.Serializable;
import java.util.UUID;
import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.MessageType;

/**
 * Response emitted by proxies indicating whether they host a specific player.
 */
@MessageType("player.locate.response")
public class PlayerLocateResponse implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;

    private UUID requestId;
    private String proxyId;
    private UUID playerId;
    private String playerName;
    private boolean found;

    public UUID getRequestId() {
        return requestId;
    }

    public void setRequestId(UUID requestId) {
        this.requestId = requestId;
    }

    public String getProxyId() {
        return proxyId;
    }

    public void setProxyId(String proxyId) {
        this.proxyId = proxyId;
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

    public boolean isFound() {
        return found;
    }

    public void setFound(boolean found) {
        this.found = found;
    }

    @Override
    public void validate() {
        if (requestId == null) {
            throw new IllegalStateException("requestId is required for PlayerLocateResponse");
        }
        if (proxyId == null || proxyId.isBlank()) {
            throw new IllegalStateException("proxyId is required for PlayerLocateResponse");
        }
    }
}
