package sh.harold.fulcrum.api.party;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-player reservation token used to transfer players into a specific server.
 */
public final class PartyReservationToken implements Serializable {
    private static final long serialVersionUID = 1L;

    private String tokenId;
    private UUID partyId;
    private UUID playerId;
    private String playerName;
    private String targetServerId;
    private long createdAt = Instant.now().toEpochMilli();
    private long expiresAt = createdAt + sh.harold.fulcrum.api.party.PartyConstants.RESERVATION_TOKEN_TTL_SECONDS * 1000L;

    public PartyReservationToken() {
        // for jackson
    }

    public PartyReservationToken(String tokenId,
                                 UUID partyId,
                                 UUID playerId,
                                 String playerName,
                                 String targetServerId,
                                 long expiresAt) {
        this.tokenId = Objects.requireNonNull(tokenId, "tokenId");
        this.partyId = Objects.requireNonNull(partyId, "partyId");
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.playerName = Objects.requireNonNull(playerName, "playerName");
        this.targetServerId = targetServerId;
        long now = Instant.now().toEpochMilli();
        this.createdAt = now;
        this.expiresAt = expiresAt > 0 ? expiresAt : now + sh.harold.fulcrum.api.party.PartyConstants.RESERVATION_TOKEN_TTL_SECONDS * 1000L;
    }

    public String getTokenId() {
        return tokenId;
    }

    public void setTokenId(String tokenId) {
        this.tokenId = tokenId;
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

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public String getTargetServerId() {
        return targetServerId;
    }

    public void setTargetServerId(String targetServerId) {
        this.targetServerId = targetServerId;
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
