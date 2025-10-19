package sh.harold.fulcrum.velocity.party;

import sh.harold.fulcrum.api.party.PartyInvite;
import sh.harold.fulcrum.api.party.PartySnapshot;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

interface PartyRepository {
    Optional<PartySnapshot> load(UUID partyId);

    void save(PartySnapshot snapshot);

    void delete(UUID partyId);

    Optional<UUID> findPartyIdForPlayer(UUID playerId);

    void assignPlayerToParty(UUID playerId, UUID partyId);

    void clearPlayerParty(UUID playerId);

    Optional<PartyInvite> findInvite(UUID playerId);

    void saveInvite(PartyInvite invite, long ttlSeconds);

    void deleteInvite(UUID playerId);

    Set<UUID> listActiveParties();

    void addActiveParty(UUID partyId);

    void removeActiveParty(UUID partyId);
}
