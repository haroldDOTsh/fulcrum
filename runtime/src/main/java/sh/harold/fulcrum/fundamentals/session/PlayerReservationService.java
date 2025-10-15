package sh.harold.fulcrum.fundamentals.session;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.messages.PlayerReservationRequest;
import sh.harold.fulcrum.api.messagebus.messages.PlayerReservationResponse;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles reservation tokens for incoming player transfers. The registry must
 * obtain a token before instructing the proxy to route a player to this server.
 */
public class PlayerReservationService {

    private static final Duration RESERVATION_TTL = Duration.ofSeconds(15);

    private final Logger logger;
    private final MessageBus messageBus;
    private final ObjectMapper objectMapper;
    private final Map<String, ReservationRecord> reservations = new ConcurrentHashMap<>();

    public PlayerReservationService(Logger logger, MessageBus messageBus) {
        this.logger = logger;
        this.messageBus = messageBus;
        this.objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public void handleReservationRequest(MessageEnvelope envelope) {
        try {
            PlayerReservationRequest request = objectMapper.treeToValue(envelope.getPayload(), PlayerReservationRequest.class);
            request.validate();

            cleanupExpired();

            String token = UUID.randomUUID().toString();
            long expiresAt = System.currentTimeMillis() + RESERVATION_TTL.toMillis();
            reservations.put(token, new ReservationRecord(request.getPlayerId(), request.getSlotId(), expiresAt));

            PlayerReservationResponse response = new PlayerReservationResponse(
                    request.getRequestId(),
                    request.getServerId(),
                    true,
                    token,
                    null
            );

            messageBus.broadcast(ChannelConstants.PLAYER_RESERVATION_RESPONSE, response);
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Failed to process reservation request", exception);
            try {
                UUID requestId = envelope.getPayload().has("requestId")
                        ? UUID.fromString(envelope.getPayload().get("requestId").asText())
                        : UUID.randomUUID();
                String serverId = envelope.getPayload().has("serverId")
                        ? envelope.getPayload().get("serverId").asText()
                        : "unknown";
                PlayerReservationResponse response = new PlayerReservationResponse(
                        requestId,
                        serverId,
                        false,
                        null,
                        exception.getMessage()
                );
                messageBus.broadcast(ChannelConstants.PLAYER_RESERVATION_RESPONSE, response);
            } catch (Exception responseException) {
                logger.log(Level.SEVERE, "Failed to publish reservation failure response", responseException);
            }
        }
    }

    public boolean consumeReservation(String token, UUID playerId) {
        if (token == null || token.isBlank()) {
            return false;
        }

        ReservationRecord record = reservations.remove(token);
        if (record == null) {
            return false;
        }

        if (record.expiresAt() < System.currentTimeMillis()) {
            logger.fine(() -> "Reservation token expired for player " + playerId);
            return false;
        }

        if (!record.playerId().equals(playerId)) {
            logger.warning("Reservation token used by mismatched player " + playerId + ", expected " + record.playerId());
            return false;
        }

        return true;
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        reservations.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
    }

    private record ReservationRecord(UUID playerId, String slotId, long expiresAt) {
    }
}
