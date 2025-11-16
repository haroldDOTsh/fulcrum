package sh.harold.fulcrum.fundamentals.playerdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import sh.harold.fulcrum.api.friends.*;
import sh.harold.fulcrum.runtime.redis.LettuceRedisOperations;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lightweight {@link FriendService} implementation for the Paper runtime.
 * Provides read-only access to Redis-backed snapshots so the privacy gate
 * can respect ignore blocks without routing every lookup through Velocity.
 */
final class RuntimeFriendSnapshotService implements FriendService {
    private static final Duration CACHE_TTL = Duration.ofSeconds(5);

    private final LettuceRedisOperations redis;
    private final Executor executor;
    private final Logger logger;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<UUID, CachedSnapshot> cache = new ConcurrentHashMap<>();

    RuntimeFriendSnapshotService(LettuceRedisOperations redis,
                                 Executor executor,
                                 Logger logger) {
        this.redis = redis;
        this.executor = executor;
        this.logger = logger;
    }

    @Override
    public CompletionStage<FriendSnapshot> getSnapshot(UUID playerId, boolean forceReload) {
        if (playerId == null) {
            return CompletableFuture.completedFuture(FriendSnapshot.empty());
        }
        if (!forceReload) {
            Optional<FriendSnapshot> cached = readCached(playerId);
            if (cached.isPresent()) {
                return CompletableFuture.completedFuture(cached.get());
            }
        }
        return fetchAsync(playerId);
    }

    @Override
    public CompletionStage<FriendOperationResult> execute(FriendMutationRequest request) {
        FriendMutationType type = request != null ? request.type() : FriendMutationType.SNAPSHOT_SYNC;
        return CompletableFuture.completedFuture(FriendOperationResult.failure(
                type,
                "Friend mutations are routed through Velocity."));
    }

    private CompletionStage<FriendSnapshot> fetchAsync(UUID playerId) {
        Executor worker = executor != null ? executor : ForkJoinPool.commonPool();
        return CompletableFuture.supplyAsync(() -> loadSnapshot(playerId), worker);
    }

    private FriendSnapshot loadSnapshot(UUID playerId) {
        if (redis == null || !redis.isAvailable()) {
            return FriendSnapshot.empty();
        }
        try {
            String payload = redis.get(FriendRedisKeys.snapshotKey(playerId));
            FriendSnapshot snapshot = payload == null || payload.isBlank()
                    ? FriendSnapshot.empty()
                    : mapper.readValue(payload, FriendSnapshot.class);
            cache.put(playerId, new CachedSnapshot(snapshot, System.currentTimeMillis()));
            return snapshot;
        } catch (Exception ex) {
            if (logger != null) {
                logger.log(Level.FINE, "Failed to load friend snapshot for " + playerId, ex);
            }
            return FriendSnapshot.empty();
        }
    }

    private Optional<FriendSnapshot> readCached(UUID playerId) {
        CachedSnapshot cached = cache.get(playerId);
        if (cached == null) {
            return Optional.empty();
        }
        if (cached.isExpired()) {
            cache.remove(playerId, cached);
            return Optional.empty();
        }
        return Optional.of(cached.snapshot());
    }

    private record CachedSnapshot(FriendSnapshot snapshot, long loadedAtMillis) {
        boolean isExpired() {
            return System.currentTimeMillis() - loadedAtMillis > CACHE_TTL.toMillis();
        }
    }
}
