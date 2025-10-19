package sh.harold.fulcrum.velocity.party;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.party.PartyInvite;
import sh.harold.fulcrum.api.party.PartyRedisKeys;
import sh.harold.fulcrum.api.party.PartySnapshot;

import java.io.IOException;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

final class RedisPartyRepository implements PartyRepository {
    private final VelocityRedisOperations redis;
    private final ObjectMapper mapper;
    private final Logger logger;

    RedisPartyRepository(VelocityRedisOperations redis, Logger logger) {
        this.redis = redis;
        this.logger = logger;
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public Optional<PartySnapshot> load(UUID partyId) {
        if (partyId == null) {
            return Optional.empty();
        }
        String raw = redis.get(PartyRedisKeys.partyDataKey(partyId));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(mapper.readValue(raw, PartySnapshot.class));
        } catch (IOException ex) {
            logger.error("Failed to deserialize party {}", partyId, ex);
            return Optional.empty();
        }
    }

    @Override
    public void save(PartySnapshot snapshot) {
        if (snapshot == null || snapshot.getPartyId() == null) {
            return;
        }
        try {
            String json = mapper.writeValueAsString(snapshot);
            redis.set(PartyRedisKeys.partyDataKey(snapshot.getPartyId()), json, 0);
            redis.sAdd(PartyRedisKeys.activePartiesSet(), snapshot.getPartyId().toString());
        } catch (Exception ex) {
            logger.error("Failed to persist party {}", snapshot.getPartyId(), ex);
        }
    }

    @Override
    public void delete(UUID partyId) {
        if (partyId == null) {
            return;
        }
        redis.delete(PartyRedisKeys.partyDataKey(partyId));
        redis.sRem(PartyRedisKeys.activePartiesSet(), partyId.toString());
    }

    @Override
    public Optional<UUID> findPartyIdForPlayer(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        String value = redis.get(PartyRedisKeys.partyMembersLookupKey(playerId));
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ex) {
            logger.warn("Invalid party id {} stored for player {}", value, playerId);
            return Optional.empty();
        }
    }

    @Override
    public void assignPlayerToParty(UUID playerId, UUID partyId) {
        if (playerId == null) {
            return;
        }
        if (partyId == null) {
            redis.delete(PartyRedisKeys.partyMembersLookupKey(playerId));
        } else {
            redis.set(PartyRedisKeys.partyMembersLookupKey(playerId), partyId.toString(), 0);
        }
    }

    @Override
    public void clearPlayerParty(UUID playerId) {
        if (playerId == null) {
            return;
        }
        redis.delete(PartyRedisKeys.partyMembersLookupKey(playerId));
    }

    @Override
    public Optional<PartyInvite> findInvite(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        String raw = redis.get(PartyRedisKeys.partyInviteKey(playerId));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(mapper.readValue(raw, PartyInvite.class));
        } catch (IOException ex) {
            logger.warn("Failed to deserialize invite for {}", playerId, ex);
            return Optional.empty();
        }
    }

    @Override
    public void saveInvite(PartyInvite invite, long ttlSeconds) {
        if (invite == null || invite.getTargetPlayerId() == null) {
            return;
        }
        try {
            redis.set(PartyRedisKeys.partyInviteKey(invite.getTargetPlayerId()),
                    mapper.writeValueAsString(invite),
                    ttlSeconds);
        } catch (Exception ex) {
            logger.warn("Failed to store invite for {}", invite.getTargetPlayerId(), ex);
        }
    }

    @Override
    public void deleteInvite(UUID playerId) {
        if (playerId == null) {
            return;
        }
        redis.delete(PartyRedisKeys.partyInviteKey(playerId));
    }

    @Override
    public Set<UUID> listActiveParties() {
        Set<String> raw = redis.sMembers(PartyRedisKeys.activePartiesSet());
        if (raw == null || raw.isEmpty()) {
            return Collections.emptySet();
        }
        return raw.stream()
                .map(value -> {
                    try {
                        return UUID.fromString(value);
                    } catch (IllegalArgumentException ex) {
                        logger.warn("Invalid party id {} found in active parties set", value);
                        return null;
                    }
                })
                .filter(uuid -> uuid != null)
                .collect(Collectors.toSet());
    }

    @Override
    public void addActiveParty(UUID partyId) {
        if (partyId == null) {
            return;
        }
        redis.sAdd(PartyRedisKeys.activePartiesSet(), partyId.toString());
    }

    @Override
    public void removeActiveParty(UUID partyId) {
        if (partyId == null) {
            return;
        }
        redis.sRem(PartyRedisKeys.activePartiesSet(), partyId.toString());
    }
}
