package sh.harold.fulcrum.api.party;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a single party participant.
 */
public final class PartyMember implements Serializable {
    private static final long serialVersionUID = 1L;

    private UUID playerId;
    private String username;
    private PartyRole role = PartyRole.MEMBER;
    private long joinedAt = Instant.now().toEpochMilli();
    private long lastSeenAt = joinedAt;
    private boolean online = true;

    public PartyMember() {
        // for jackson
    }

    public PartyMember(UUID playerId, String username, PartyRole role) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.username = Objects.requireNonNull(username, "username");
        this.role = Objects.requireNonNullElse(role, PartyRole.MEMBER);
        long now = Instant.now().toEpochMilli();
        this.joinedAt = now;
        this.lastSeenAt = now;
        this.online = true;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public void setPlayerId(UUID playerId) {
        this.playerId = playerId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public PartyRole getRole() {
        return role;
    }

    public void setRole(PartyRole role) {
        this.role = Objects.requireNonNullElse(role, PartyRole.MEMBER);
    }

    public long getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(long joinedAt) {
        this.joinedAt = joinedAt;
    }

    public long getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(long lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }
}
