package sh.harold.fulcrum.api.messagebus.messages;

import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.MessageType;

import java.io.Serializable;
import java.util.UUID;

/**
 * Sent by proxies to acknowledge the outcome of a player routing command.
 */
@MessageType("player.route.ack")
public class PlayerRouteAck implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;
    private UUID requestId;
    private UUID playerId;
    private String proxyId;
    private String serverId;
    private String slotId;
    private Status status = Status.SUCCESS;
    private String reason;
    public PlayerRouteAck() {
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

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    @Override
    public void validate() {
        if (requestId == null) {
            throw new IllegalStateException("requestId is required for PlayerRouteAck");
        }
        if (playerId == null) {
            throw new IllegalStateException("playerId is required for PlayerRouteAck");
        }
        if (proxyId == null || proxyId.isBlank()) {
            throw new IllegalStateException("proxyId is required for PlayerRouteAck");
        }
        if (status == null) {
            throw new IllegalStateException("status is required for PlayerRouteAck");
        }
        if (status == Status.SUCCESS && (serverId == null || serverId.isBlank())) {
            throw new IllegalStateException("serverId is required for successful PlayerRouteAck");
        }
        if (status == Status.SUCCESS && (slotId == null || slotId.isBlank())) {
            throw new IllegalStateException("slotId is required for successful PlayerRouteAck");
        }
    }

    public enum Status {
        SUCCESS,
        FAILED
    }
}
