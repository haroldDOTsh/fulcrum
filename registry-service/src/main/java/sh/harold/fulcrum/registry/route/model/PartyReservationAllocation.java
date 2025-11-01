package sh.harold.fulcrum.registry.route.model;

import sh.harold.fulcrum.api.party.PartyReservationSnapshot;
import sh.harold.fulcrum.api.party.PartyReservationToken;
import sh.harold.fulcrum.registry.route.store.RedisRoutingStore;
import sh.harold.fulcrum.registry.route.util.SlotIdUtils;
import sh.harold.fulcrum.registry.slot.LogicalSlotRecord;

import java.util.*;

/**
 * Mutable view of a party reservation while it is routed through the registry.
 */
public final class PartyReservationAllocation {
    private final PartyReservationSnapshot snapshot;
    private final String reservationId;
    private final String familyId;
    private final String variantId;
    private final String slotId;
    private final String slotSuffix;
    private final String serverId;
    private final Map<UUID, PartyReservationToken> tokensByPlayer;
    private final Map<String, UUID> playerByToken;
    private final Set<UUID> dispatchedPlayers;
    private final Set<UUID> claimedPlayers;
    private final Map<UUID, String> claimFailures;
    private final int partySize;
    private final int teamIndex;
    private boolean released;
    private long allocatedAt;

    public PartyReservationAllocation(PartyReservationSnapshot snapshot,
                                      LogicalSlotRecord slot,
                                      String familyId,
                                      String variantId,
                                      int teamIndex) {
        this(snapshot,
                slot.getSlotId(),
                slot.getSlotSuffix(),
                slot.getServerId(),
                familyId,
                variantId,
                teamIndex,
                false,
                System.currentTimeMillis(),
                snapshot.getTokens() != null ? snapshot.getTokens().size() : 0,
                snapshot.getTokens());
    }

    public PartyReservationAllocation(PartyReservationSnapshot snapshot,
                                      String slotId,
                                      String slotSuffix,
                                      String serverId,
                                      String familyId,
                                      String variantId,
                                      int teamIndex,
                                      boolean released,
                                      long allocatedAt,
                                      int partySize,
                                      Map<UUID, PartyReservationToken> tokens) {
        this.snapshot = snapshot;
        this.reservationId = snapshot.getReservationId();
        this.familyId = familyId;
        this.variantId = variantId;
        this.slotId = SlotIdUtils.sanitize(slotId);
        this.slotSuffix = slotSuffix;
        this.serverId = serverId;
        this.tokensByPlayer = tokens != null ? new LinkedHashMap<>(tokens) : Collections.emptyMap();
        this.playerByToken = new HashMap<>();
        this.tokensByPlayer.forEach((playerId, token) -> playerByToken.put(token.getTokenId(), playerId));
        this.partySize = partySize > 0 ? partySize : this.tokensByPlayer.size();
        this.teamIndex = teamIndex;
        this.released = released;
        this.allocatedAt = allocatedAt;
        this.dispatchedPlayers = new HashSet<>();
        this.claimedPlayers = new HashSet<>();
        this.claimFailures = new HashMap<>();
    }

    public static PartyReservationAllocation fromEntry(RedisRoutingStore.PartyAllocationEntry entry) {
        PartyReservationAllocation allocation = new PartyReservationAllocation(
                entry.getReservation(),
                entry.getSlotId(),
                entry.getSlotSuffix(),
                entry.getServerId(),
                entry.getFamilyId(),
                entry.getVariantId(),
                entry.getTeamIndex(),
                entry.isReleased(),
                entry.getAllocatedAt(),
                entry.getPartySize(),
                entry.getReservation() != null ? entry.getReservation().getTokens() : Map.of()
        );
        allocation.dispatchedPlayers.clear();
        allocation.dispatchedPlayers.addAll(entry.getDispatchedPlayers());
        allocation.claimedPlayers.clear();
        allocation.claimedPlayers.addAll(entry.getClaimedPlayers());
        allocation.claimFailures.clear();
        allocation.claimFailures.putAll(entry.getClaimFailures());
        return allocation;
    }

    public RedisRoutingStore.PartyAllocationEntry toEntry() {
        return new RedisRoutingStore.PartyAllocationEntry(
                snapshot,
                reservationId,
                familyId,
                variantId,
                slotId,
                slotSuffix,
                serverId,
                partySize,
                teamIndex,
                released,
                allocatedAt,
                Set.copyOf(dispatchedPlayers),
                Set.copyOf(claimedPlayers),
                Map.copyOf(claimFailures)
        );
    }

    public PartyReservationSnapshot snapshot() {
        return snapshot;
    }

    public String reservationId() {
        return reservationId;
    }

    public String familyId() {
        return familyId;
    }

    public String variantId() {
        return variantId;
    }

    public String slotId() {
        return slotId;
    }

    public String slotSuffix() {
        return slotSuffix;
    }

    public String serverId() {
        return serverId;
    }

    public int partySize() {
        return partySize;
    }

    public int teamIndex() {
        return teamIndex;
    }

    public boolean isReleased() {
        return released;
    }

    public void release() {
        released = true;
    }

    public void setAllocatedAt(long allocatedAt) {
        this.allocatedAt = allocatedAt;
    }

    public PartyReservationToken getTokenForPlayer(UUID playerId) {
        return tokensByPlayer.get(playerId);
    }

    public boolean markDispatched(UUID playerId) {
        return dispatchedPlayers.add(playerId);
    }

    public boolean onRouteCompleted(UUID playerId) {
        dispatchedPlayers.add(playerId);
        if (dispatchedPlayers.size() >= partySize) {
            released = true;
            return true;
        }
        return false;
    }

    public ClaimProgress recordClaim(UUID playerId, boolean success, String reason) {
        if (playerId != null) {
            if (success) {
                claimedPlayers.add(playerId);
                claimFailures.remove(playerId);
            } else {
                claimedPlayers.remove(playerId);
                claimFailures.put(playerId, reason != null ? reason : "unknown");
            }
        }

        Set<UUID> expected = new HashSet<>(tokensByPlayer.keySet());
        expected.removeAll(claimedPlayers);
        expected.removeAll(claimFailures.keySet());

        int processed = claimedPlayers.size() + claimFailures.size();
        boolean complete = partySize > 0 && processed >= partySize;
        boolean successful = complete && claimFailures.isEmpty() && claimedPlayers.size() >= partySize;

        return new ClaimProgress(
                complete,
                successful,
                Map.copyOf(claimFailures),
                Set.copyOf(expected)
        );
    }

    public Map<UUID, String> claimFailures() {
        return claimFailures;
    }

    public Set<UUID> dispatchedPlayers() {
        return dispatchedPlayers;
    }

    public Set<UUID> claimedPlayers() {
        return claimedPlayers;
    }

    public record ClaimProgress(boolean complete, boolean success, Map<UUID, String> failures, Set<UUID> missing) {

        public Set<UUID> missingPlayers() {
            return missing;
        }
    }
}
