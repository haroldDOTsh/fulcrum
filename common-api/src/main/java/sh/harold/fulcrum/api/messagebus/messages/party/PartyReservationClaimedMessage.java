package sh.harold.fulcrum.api.messagebus.messages.party;

import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.MessageType;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@MessageType(value = "party.reservation.claimed", version = 1)
public final class PartyReservationClaimedMessage implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;

    private UUID eventId = UUID.randomUUID();
    private String reservationId;
    private UUID partyId;
    private UUID playerId;
    private String serverId;
    private boolean success = true;
    private String reason;
    private long timestamp = Instant.now().toEpochMilli();

    public PartyReservationClaimedMessage() {
        // jackson
    }

    public UUID getEventId() {
        return eventId;
    }

    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    public String getReservationId() {
        return reservationId;
    }

    public void setReservationId(String reservationId) {
        this.reservationId = reservationId;
    }

    public UUID getPartyId() {
        return partyId;
    }

    public void setPartyId(UUID partyId) {
        this.partyId = partyId;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public void setPlayerId(UUID playerId) {
        this.playerId = playerId;
    }

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public void validate() {
        if (eventId == null) {
            throw new IllegalStateException("eventId is required");
        }
        if (reservationId == null || reservationId.isBlank()) {
            throw new IllegalStateException("reservationId is required");
        }
        if (partyId == null) {
            throw new IllegalStateException("partyId is required");
        }
        if (playerId == null) {
            throw new IllegalStateException("playerId is required");
        }
    }
}
