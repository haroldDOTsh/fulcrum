package sh.harold.fulcrum.api.messagebus.messages.match;

import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.MessageType;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@MessageType("match.roster.created")
public final class MatchRosterCreatedMessage implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;

    private UUID eventId = UUID.randomUUID();
    private UUID matchId;
    private String serverId;
    private String slotId;
    private String familyId;
    private String variantId;
    private Set<UUID> players = Collections.emptySet();
    private long createdAt = Instant.now().toEpochMilli();

    public MatchRosterCreatedMessage() {
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

    public Set<UUID> getPlayers() {
        return Collections.unmodifiableSet(players);
    }

    public void setPlayers(Set<UUID> players) {
        this.players = players != null ? new LinkedHashSet<>(players) : Collections.emptySet();
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
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
        if (players == null || players.isEmpty()) {
            throw new IllegalStateException("players is required");
        }
    }
}
