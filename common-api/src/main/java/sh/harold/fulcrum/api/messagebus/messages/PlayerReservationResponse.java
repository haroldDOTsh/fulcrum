package sh.harold.fulcrum.api.messagebus.messages;

import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;

import java.io.Serializable;
import java.util.UUID;

/**
 * Response published by backend servers after handling a reservation request.
 */
public class PlayerReservationResponse implements BaseMessage, Serializable {

    private UUID requestId;
    private String serverId;
    private boolean accepted;
    private String reservationToken;
    private String reason;

    public PlayerReservationResponse() {
    }

    public PlayerReservationResponse(UUID requestId,
                                     String serverId,
                                     boolean accepted,
                                     String reservationToken,
                                     String reason) {
        this.requestId = requestId;
        this.serverId = serverId;
        this.accepted = accepted;
        this.reservationToken = reservationToken;
        this.reason = reason;
    }

    @Override
    public String getMessageType() {
        return ChannelConstants.PLAYER_RESERVATION_RESPONSE;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public void setRequestId(UUID requestId) {
        this.requestId = requestId;
    }

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public void setAccepted(boolean accepted) {
        this.accepted = accepted;
    }

    public String getReservationToken() {
        return reservationToken;
    }

    public void setReservationToken(String reservationToken) {
        this.reservationToken = reservationToken;
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
            throw new IllegalStateException("requestId is required for PlayerReservationResponse");
        }
        if (serverId == null || serverId.isBlank()) {
            throw new IllegalStateException("serverId is required for PlayerReservationResponse");
        }
        if (accepted && (reservationToken == null || reservationToken.isBlank())) {
            throw new IllegalStateException("reservationToken is required when reservation is accepted");
        }
    }
}
