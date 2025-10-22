package sh.harold.fulcrum.api.party;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a pending invite into a party.
 */
public final class PartyInvite implements Serializable {
    private static final long serialVersionUID = 1L;

    private UUID partyId;
    private UUID targetPlayerId;
    private String targetUsername;
    private UUID inviterPlayerId;
    private String inviterUsername;
    private long createdAt = Instant.now().toEpochMilli();
    private long expiresAt = createdAt + 60_000L;

    public PartyInvite() {
        // for jackson
    }

    public PartyInvite(UUID partyId,
                       UUID targetPlayerId,
                       String targetUsername,
                       UUID inviterPlayerId,
                       String inviterUsername,
                       long expiresAt) {
        this.partyId = Objects.requireNonNull(partyId, "partyId");
        this.targetPlayerId = Objects.requireNonNull(targetPlayerId, "targetPlayerId");
        this.targetUsername = Objects.requireNonNull(targetUsername, "targetUsername");
        this.inviterPlayerId = Objects.requireNonNull(inviterPlayerId, "inviterPlayerId");
        this.inviterUsername = Objects.requireNonNull(inviterUsername, "inviterUsername");
        long now = Instant.now().toEpochMilli();
        this.createdAt = now;
        this.expiresAt = expiresAt > now ? expiresAt : now + 60_000L;
    }

    public UUID getPartyId() {
        return partyId;
    }

    public void setPartyId(UUID partyId) {
        this.partyId = partyId;
    }

    public UUID getTargetPlayerId() {
        return targetPlayerId;
    }

    public void setTargetPlayerId(UUID targetPlayerId) {
        this.targetPlayerId = targetPlayerId;
    }

    public String getTargetUsername() {
        return targetUsername;
    }

    public void setTargetUsername(String targetUsername) {
        this.targetUsername = targetUsername;
    }

    public UUID getInviterPlayerId() {
        return inviterPlayerId;
    }

    public void setInviterPlayerId(UUID inviterPlayerId) {
        this.inviterPlayerId = inviterPlayerId;
    }

    public String getInviterUsername() {
        return inviterUsername;
    }

    public void setInviterUsername(String inviterUsername) {
        this.inviterUsername = inviterUsername;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(long expiresAt) {
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(long now) {
        return expiresAt > 0 && now >= expiresAt;
    }
}
