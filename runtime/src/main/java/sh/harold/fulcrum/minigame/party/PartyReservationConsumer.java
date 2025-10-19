package sh.harold.fulcrum.minigame.party;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.messages.party.PartyReservationClaimedMessage;
import sh.harold.fulcrum.api.party.PartyConstants;
import sh.harold.fulcrum.api.party.PartyRedisKeys;
import sh.harold.fulcrum.api.party.PartyReservationSnapshot;
import sh.harold.fulcrum.api.party.PartyReservationToken;
import sh.harold.fulcrum.runtime.redis.LettuceRedisOperations;

import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Validates party reservation tokens issued by the proxy and announces
 * consumption back to the network so other services can update state.
 */
public final class PartyReservationConsumer {

    private final LettuceRedisOperations redis;
    private final MessageBus messageBus;
    private final Logger logger;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public PartyReservationConsumer(LettuceRedisOperations redis,
                                    MessageBus messageBus,
                                    Logger logger) {
        this.redis = redis;
        this.messageBus = messageBus;
        this.logger = logger;
    }

    public boolean consume(String reservationId,
                           String tokenId,
                           UUID playerId,
                           String serverId,
                           String slotId) {
        if (reservationId == null || reservationId.isBlank() || tokenId == null || tokenId.isBlank()) {
            publish(reservationId, null, playerId, serverId, false, "token-missing");
            return false;
        }
        if (redis == null) {
            logger.warning(String.format("Redis unavailable; accepting party reservation %s for player %s without validation", reservationId, playerId));
            publish(reservationId, null, playerId, serverId, true, null);
            return true;
        }

        String lockKey = PartyRedisKeys.partyReservationKey(reservationId) + ":lock";
        String lockToken = UUID.randomUUID().toString();
        if (!redis.setIfAbsent(lockKey, lockToken, 5)) {
            publish(reservationId, null, playerId, serverId, false, "reservation-locked");
            return false;
        }

        try {
            String snapshotKey = PartyRedisKeys.partyReservationKey(reservationId);
            String payload = redis.get(snapshotKey);
            if (payload == null || payload.isBlank()) {
                publish(reservationId, null, playerId, serverId, false, "reservation-missing");
                return false;
            }

            PartyReservationSnapshot snapshot = mapper.readValue(payload, PartyReservationSnapshot.class);
            Map<UUID, PartyReservationToken> tokens = snapshot.getTokens();
            if (tokens == null || tokens.isEmpty()) {
                publish(reservationId, snapshot.getPartyId(), playerId, serverId, false, "tokens-exhausted");
                return false;
            }

            PartyReservationToken token = tokens.get(playerId);
            if (token == null) {
                publish(reservationId, snapshot.getPartyId(), playerId, serverId, false, "token-player-mismatch");
                return false;
            }
            if (!token.getTokenId().equals(tokenId)) {
                publish(reservationId, snapshot.getPartyId(), playerId, serverId, false, "token-mismatch");
                return false;
            }
            if (token.isExpired(System.currentTimeMillis())) {
                publish(reservationId, snapshot.getPartyId(), playerId, serverId, false, "token-expired");
                return false;
            }

            tokens.remove(playerId);
            snapshot.setTokens(tokens);

            if (tokens.isEmpty()) {
                redis.delete(snapshotKey);
            } else {
                redis.set(snapshotKey, mapper.writeValueAsString(snapshot), PartyConstants.RESERVATION_TOKEN_TTL_SECONDS);
            }

            publish(reservationId, snapshot.getPartyId(), playerId, serverId, true, null);
            return true;
        } catch (Exception ex) {
            logger.warning(String.format("Failed to validate party reservation %s for player %s: %s", reservationId, playerId, ex.getMessage()));
            publish(reservationId, null, playerId, serverId, false, "validation-error");
            return false;
        } finally {
            redis.deleteIfMatches(lockKey, lockToken);
        }
    }

    private void publish(String reservationId,
                         UUID partyId,
                         UUID playerId,
                         String serverId,
                         boolean success,
                         String reason) {
        if (messageBus == null || reservationId == null) {
            return;
        }
        try {
            PartyReservationClaimedMessage message = new PartyReservationClaimedMessage();
            message.setReservationId(reservationId);
            message.setPartyId(partyId);
            message.setPlayerId(playerId);
            message.setServerId(serverId);
            message.setSuccess(success);
            message.setReason(reason);
            messageBus.broadcast(ChannelConstants.PARTY_RESERVATION_CLAIMED, message);
        } catch (Exception ex) {
            logger.warning("Failed to publish PartyReservationClaimedMessage: " + ex.getMessage());
        }
    }
}
