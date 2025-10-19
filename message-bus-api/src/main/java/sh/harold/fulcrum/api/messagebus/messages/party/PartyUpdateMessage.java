package sh.harold.fulcrum.api.messagebus.messages.party;

import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.MessageType;
import sh.harold.fulcrum.api.party.PartySnapshot;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Broadcast whenever party state changes so other services can synchronize.
 */
@MessageType("party.update")
public final class PartyUpdateMessage implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;

    private UUID eventId = UUID.randomUUID();
    private PartyMessageAction action = PartyMessageAction.UPDATED;
    private UUID partyId;
    private UUID actorPlayerId;
    private UUID targetPlayerId;
    private String context;
    private String reason;
    private PartySnapshot snapshot;
    private long timestamp = Instant.now().toEpochMilli();

    public PartyUpdateMessage() {
        // jackson
    }

    public UUID getEventId() {
        return eventId;
    }

    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    public PartyMessageAction getAction() {
        return action;
    }

    public void setAction(PartyMessageAction action) {
        this.action = action;
    }

    public UUID getPartyId() {
        return partyId;
    }

    public void setPartyId(UUID partyId) {
        this.partyId = partyId;
    }

    public UUID getActorPlayerId() {
        return actorPlayerId;
    }

    public void setActorPlayerId(UUID actorPlayerId) {
        this.actorPlayerId = actorPlayerId;
    }

    public UUID getTargetPlayerId() {
        return targetPlayerId;
    }

    public void setTargetPlayerId(UUID targetPlayerId) {
        this.targetPlayerId = targetPlayerId;
    }

    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public PartySnapshot getSnapshot() {
        return snapshot;
    }

    public void setSnapshot(PartySnapshot snapshot) {
        this.snapshot = snapshot;
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
        if (action == null) {
            throw new IllegalStateException("action is required");
        }
        if (partyId == null) {
            throw new IllegalStateException("partyId is required");
        }
    }
}
