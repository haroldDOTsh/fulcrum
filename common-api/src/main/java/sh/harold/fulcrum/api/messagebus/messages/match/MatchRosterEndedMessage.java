package sh.harold.fulcrum.api.messagebus.messages.match;

import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.MessageType;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@MessageType(value = "match.roster.ended", version = 1)
public final class MatchRosterEndedMessage implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;

    private UUID eventId = UUID.randomUUID();
    private UUID matchId;
    private String slotId;
    private String serverId;
    private long endedAt = Instant.now().toEpochMilli();

    public MatchRosterEndedMessage() {
        // for jackson
    }

    public UUID getEventId() {
        return eventId;
    }

    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    public UUID getMatchId() {
        return matchId;
    }

    public void setMatchId(UUID matchId) {
        this.matchId = matchId;
    }

    public String getSlotId() {
        return slotId;
    }

    public void setSlotId(String slotId) {
        this.slotId = slotId;
    }

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public long getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(long endedAt) {
        this.endedAt = endedAt;
    }

    @Override
    public void validate() {
        if (eventId == null) {
            throw new IllegalStateException("eventId is required");
        }
        if (matchId == null) {
            throw new IllegalStateException("matchId is required");
        }
        if (slotId == null || slotId.isBlank()) {
            throw new IllegalStateException("slotId is required");
        }
    }
}
