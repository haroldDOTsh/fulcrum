package sh.harold.fulcrum.velocity.party;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.party.PartyConstants;
import sh.harold.fulcrum.api.party.PartyReservationSnapshot;

import java.io.IOException;
import java.util.Optional;

final class PartyReservationStore {
    private final VelocityRedisOperations redis;
    private final ObjectMapper mapper;
    private final Logger logger;

    PartyReservationStore(VelocityRedisOperations redis, Logger logger) {
        this.redis = redis;
        this.logger = logger;
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    void save(PartyReservationSnapshot snapshot) {
        if (snapshot == null || snapshot.getReservationId() == null) {
            return;
        }
        try {
            String payload = mapper.writeValueAsString(snapshot);
            redis.set(snapshotKey(snapshot.getReservationId()), payload, PartyConstants.RESERVATION_TOKEN_TTL_SECONDS);
        } catch (Exception ex) {
            logger.warn("Failed to persist party reservation {}", snapshot.getReservationId(), ex);
        }
    }

    Optional<PartyReservationSnapshot> get(String reservationId) {
        if (reservationId == null || reservationId.isBlank()) {
            return Optional.empty();
        }
        String payload = redis.get(snapshotKey(reservationId));
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(payload, PartyReservationSnapshot.class));
        } catch (IOException ex) {
            logger.warn("Failed to deserialize reservation {}", reservationId, ex);
            return Optional.empty();
        }
    }

    void delete(String reservationId) {
        if (reservationId == null || reservationId.isBlank()) {
            return;
        }
        redis.delete(snapshotKey(reservationId));
    }

    private String snapshotKey(String reservationId) {
        return sh.harold.fulcrum.api.party.PartyRedisKeys.partyReservationKey(reservationId);
    }
}
