package sh.harold.fulcrum.api.messagebus.messages.party;

import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.MessageType;
import sh.harold.fulcrum.api.party.PartyReservationSnapshot;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@MessageType(value = "party.reservation.created", version = 1)
public final class PartyReservationCreatedMessage implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;

    private UUID eventId = UUID.randomUUID();
    private String reservationId;
    private UUID partyId;
    private String familyId;
    private String variantId;
    private String targetServerId;
    private PartyReservationSnapshot reservation;
    private long timestamp = Instant.now().toEpochMilli();

    public PartyReservationCreatedMessage() {
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

    public String getFamilyId() {
        return familyId;
    }

    public void setFamilyId(String familyId) {
        this.familyId = familyId;
    }

    public String getVariantId() {
        return variantId;
    }

    public void setVariantId(String variantId) {
        this.variantId = variantId;
    }

    public String getTargetServerId() {
        return targetServerId;
    }

    public void setTargetServerId(String targetServerId) {
        this.targetServerId = targetServerId;
    }

    public PartyReservationSnapshot getReservation() {
        return reservation;
    }

    public void setReservation(PartyReservationSnapshot reservation) {
        this.reservation = reservation;
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
    }
}
