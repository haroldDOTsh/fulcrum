package sh.harold.fulcrum.velocity.party;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

final class PartyLockManager {
    private static final String LOCK_PREFIX = "fulcrum:party:lock:";

    private final VelocityRedisOperations redis;
    private final long lockTtlSeconds;

    PartyLockManager(VelocityRedisOperations redis, Duration ttl) {
        this.redis = redis;
        this.lockTtlSeconds = Math.max(1L, ttl != null ? ttl.toSeconds() : 5L);
    }

    Optional<String> acquire(UUID partyId) {
        if (partyId == null) {
            return Optional.empty();
        }
        String token = UUID.randomUUID().toString();
        boolean acquired = redis.setIfAbsent(LOCK_PREFIX + partyId, token, lockTtlSeconds);
        return acquired ? Optional.of(token) : Optional.empty();
    }

    void release(UUID partyId, String token) {
        if (partyId == null || token == null) {
            return;
        }
        redis.deleteIfMatches(LOCK_PREFIX + partyId, token);
    }
}
