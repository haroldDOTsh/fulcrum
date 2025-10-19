package sh.harold.fulcrum.api.party;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Canonical representation of a party persisted in Redis.
 */
public final class PartySnapshot implements Serializable {
    private static final long serialVersionUID = 1L;

    private UUID partyId;
    private long createdAt = Instant.now().toEpochMilli();
    private UUID leaderId;
    private Map<UUID, PartyMember> members = new LinkedHashMap<>();
    private Map<UUID, PartyInvite> invites = new LinkedHashMap<>();
    private PartySettings settings = PartySettings.defaults();
    private long lastActivityAt = createdAt;
    private long pendingIdleDisbandAt = 0L;
    private String activeReservationId;
    private String activeServerId;

    public PartySnapshot() {
        // for jackson
    }

    public UUID getPartyId() {
        return partyId;
    }

    public void setPartyId(UUID partyId) {
        this.partyId = partyId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public UUID getLeaderId() {
        return leaderId;
    }

    public void setLeaderId(UUID leaderId) {
        this.leaderId = leaderId;
    }

    public Map<UUID, PartyMember> getMembers() {
        return members;
    }

    public void setMembers(Map<UUID, PartyMember> members) {
        this.members = members != null ? new LinkedHashMap<>(members) : new LinkedHashMap<>();
    }

    public Map<UUID, PartyInvite> getInvites() {
        return invites;
    }

    public void setInvites(Map<UUID, PartyInvite> invites) {
        this.invites = invites != null ? new LinkedHashMap<>(invites) : new LinkedHashMap<>();
    }

    public PartySettings getSettings() {
        return settings;
    }

    public void setSettings(PartySettings settings) {
        this.settings = Objects.requireNonNullElseGet(settings, PartySettings::defaults);
    }

    public long getLastActivityAt() {
        return lastActivityAt;
    }

    public void setLastActivityAt(long lastActivityAt) {
        this.lastActivityAt = lastActivityAt;
    }

    public long getPendingIdleDisbandAt() {
        return pendingIdleDisbandAt;
    }

    public void setPendingIdleDisbandAt(long pendingIdleDisbandAt) {
        this.pendingIdleDisbandAt = pendingIdleDisbandAt;
    }

    public String getActiveReservationId() {
        return activeReservationId;
    }

    public void setActiveReservationId(String activeReservationId) {
        this.activeReservationId = activeReservationId;
    }

    public String getActiveServerId() {
        return activeServerId;
    }

    public void setActiveServerId(String activeServerId) {
        this.activeServerId = activeServerId;
    }

    @JsonIgnore
    public int getSize() {
        return members != null ? members.size() : 0;
    }

    @JsonIgnore
    public boolean isMember(UUID playerId) {
        return playerId != null && members.containsKey(playerId);
    }

    @JsonIgnore
    public PartyMember getMember(UUID playerId) {
        return playerId == null ? null : members.get(playerId);
    }
}
