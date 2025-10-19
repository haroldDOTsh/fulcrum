package sh.harold.fulcrum.velocity.party;

import sh.harold.fulcrum.api.party.PartyInvite;
import sh.harold.fulcrum.api.party.PartySnapshot;

import java.util.Optional;
import java.util.UUID;

public interface PartyService {
    Optional<PartySnapshot> getParty(UUID partyId);

    Optional<PartySnapshot> getPartyByPlayer(UUID playerId);

    PartyOperationResult createParty(UUID leaderId, String leaderName);

    PartyOperationResult invitePlayer(UUID actorId, String actorName, UUID targetId, String targetName);

    PartyOperationResult acceptInvite(UUID playerId, String playerName);

    PartyOperationResult declineInvite(UUID playerId);

    PartyOperationResult leaveParty(UUID playerId);

    PartyOperationResult disbandParty(UUID actorId);

    PartyOperationResult promote(UUID actorId, UUID targetId);

    PartyOperationResult demote(UUID actorId, UUID targetId);

    PartyOperationResult transferLeadership(UUID actorId, UUID targetId);

    PartyOperationResult kick(UUID actorId, UUID targetId);

    PartyOperationResult kickOffline(UUID actorId, long offlineThresholdMillis);

    PartyOperationResult toggleMute(UUID actorId, boolean muted);

    PartyOperationResult updateSettings(UUID actorId, PartySettingsMutator mutator);

    Optional<PartyInvite> getInvite(UUID playerId);

    void refreshPresence(UUID playerId, String username, boolean online);

    void purgeExpiredInvites();

    PartyOperationResult setActiveReservation(UUID partyId, String reservationId, String targetServerId);

    PartyOperationResult clearActiveReservation(UUID partyId, String reservationId, boolean success, String reason);
}
