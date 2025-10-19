package sh.harold.fulcrum.api.party;

import java.io.Serializable;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregates reservation tokens for a party attempting to join a minigame instance.
 */
public final class PartyReservationSnapshot implements Serializable {
    private static final long serialVersionUID = 1L;

    private String reservationId;
    private UUID partyId;
    private String familyId;
    private String variantId;
    private String targetServerId;
    private long createdAt = Instant.now().toEpochMilli();
    private long expiresAt = createdAt + sh.harold.fulcrum.api.party.PartyConstants.RESERVATION_TOKEN_TTL_SECONDS * 1000L;
    private Map<UUID, PartyReservationToken> tokens = new LinkedHashMap<>();
    private Integer assignedTeamIndex;

    public PartyReservationSnapshot() {
        // for jackson
    }

    public PartyReservationSnapshot(String reservationId, UUID partyId) {
        this.reservationId = Objects.requireNonNull(reservationId, "reservationId");
        this.partyId = Objects.requireNonNull(partyId, "partyId");
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

    public Map<UUID, PartyReservationToken> getTokens() {
        return tokens;
    }

    public void setTokens(Map<UUID, PartyReservationToken> tokens) {
        this.tokens = tokens != null ? new LinkedHashMap<>(tokens) : new LinkedHashMap<>();
    }

    public Integer getAssignedTeamIndex() {
        return assignedTeamIndex;
    }

    public void setAssignedTeamIndex(Integer assignedTeamIndex) {
        this.assignedTeamIndex = assignedTeamIndex;
    }

    public boolean isExpired(long now) {
        return expiresAt > 0 && now >= expiresAt;
    }
}
