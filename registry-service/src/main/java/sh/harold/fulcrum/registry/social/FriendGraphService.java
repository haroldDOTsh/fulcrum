package sh.harold.fulcrum.registry.social;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.api.sync.RedisCommands;
import sh.harold.fulcrum.api.friends.*;
import sh.harold.fulcrum.api.messagebus.messages.social.FriendBlockEventMessage;
import sh.harold.fulcrum.api.messagebus.messages.social.FriendRelationEventMessage;
import sh.harold.fulcrum.api.messagebus.messages.social.FriendRequestEventMessage;
import sh.harold.fulcrum.registry.redis.RedisManager;
import sh.harold.fulcrum.registry.social.FriendGraphRepository.BlockChange;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Coordinates persistence with Redis caching and event construction.
 */
public final class FriendGraphService {
    private final FriendGraphRepository repository;
    private final RedisManager redisManager;
    private final Logger logger;
    private final ObjectMapper mapper = new ObjectMapper();

    public FriendGraphService(FriendGraphRepository repository,
                              RedisManager redisManager,
                              Logger logger) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.redisManager = Objects.requireNonNull(redisManager, "redisManager");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public FriendOperationContext apply(FriendMutationRequest request) {
        try {
            FriendGraphMutationResult dbResult = repository.applyMutation(request);
            if (!dbResult.success()) {
                FriendOperationResult failure = FriendOperationResult.failure(request.type(), dbResult.error());
                return new FriendOperationContext(failure, null, null);
            }

            if (dbResult.actorSnapshot() != null) {
                cacheSnapshot(dbResult.actorId(), dbResult.actorSnapshot());
            }
            if (dbResult.targetSnapshot() != null) {
                cacheSnapshot(dbResult.targetId(), dbResult.targetSnapshot());
            }

            FriendOperationResult success = FriendOperationResult.success(request.type(),
                    dbResult.actorSnapshot(), dbResult.targetSnapshot());
            FriendRelationEventMessage relationEvent = buildRelationEvent(dbResult);
            FriendBlockEventMessage blockEvent = buildBlockEvent(dbResult);
            return new FriendOperationContext(success, relationEvent, blockEvent);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to apply friend mutation", e);
            return new FriendOperationContext(FriendOperationResult.failure(request.type(), e.getMessage()), null, null);
        }
    }

    public FriendSnapshot getSnapshot(UUID playerId, boolean forceReload) {
        if (!forceReload) {
            Optional<FriendSnapshot> cached = readCachedSnapshot(playerId);
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        try {
            FriendSnapshot snapshot = repository.loadSnapshot(playerId);
            cacheSnapshot(playerId, snapshot);
            return snapshot;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to load snapshot for " + playerId, e);
            return FriendSnapshot.empty();
        }
    }

    public List<FriendBlockEventMessage> purgeExpiredBlocks() {
        try {
            List<BlockChange> expired = repository.purgeExpiredBlocks(Instant.now());
            List<FriendBlockEventMessage> events = new ArrayList<>(expired.size());
            for (BlockChange change : expired) {
                FriendSnapshot ownerSnapshot = repository.loadSnapshot(change.ownerId());
                FriendSnapshot peerSnapshot = repository.loadSnapshot(change.peerId());
                cacheSnapshot(change.ownerId(), ownerSnapshot);
                cacheSnapshot(change.peerId(), peerSnapshot);
                FriendBlockEventMessage event = new FriendBlockEventMessage();
                event.setOwnerId(change.ownerId());
                event.setTargetId(change.peerId());
                event.setScope(change.scope());
                event.setActive(false);
                event.setOwnerVersion(ownerSnapshot.version());
                event.setTargetVersion(peerSnapshot.version());
                event.setUpdatedAtEpochMillis(Instant.now().toEpochMilli());
                events.add(event);
            }
            return events;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to purge expired friend blocks", e);
            return List.of();
        }
    }

    private FriendRelationEventMessage buildRelationEvent(FriendGraphMutationResult result) {
        if (!result.success()) {
            return null;
        }
        boolean isRelationMutation = switch (result.mutationType()) {
            case INVITE_SEND, INVITE_ACCEPT, INVITE_DECLINE, INVITE_CANCEL, UNFRIEND -> true;
            default -> false;
        };
        if (!isRelationMutation) {
            return null;
        }
        FriendRelationEventMessage event = isRequestEvent(result.mutationType())
                ? new FriendRequestEventMessage()
                : new FriendRelationEventMessage();
        event.setOwnerId(result.actorId());
        event.setPeerId(result.targetId());
        event.setOwnerState(result.actorState());
        event.setPeerState(result.targetState());
        event.setMutationType(result.mutationType());
        event.setOwnerVersion(result.actorRelationVersion());
        event.setPeerVersion(result.targetRelationVersion());
        event.setRelationVersion(Math.max(result.actorRelationVersion(), result.targetRelationVersion()));
        event.setUpdatedAtEpochMillis(result.updatedAt() != null ? result.updatedAt().toEpochMilli() : System.currentTimeMillis());
        return event;
    }

    private FriendBlockEventMessage buildBlockEvent(FriendGraphMutationResult result) {
        if (!result.success()) {
            return null;
        }
        if (result.mutationType() != FriendMutationType.BLOCK && result.mutationType() != FriendMutationType.UNBLOCK) {
            return null;
        }
        FriendBlockEventMessage event = new FriendBlockEventMessage();
        event.setOwnerId(result.actorId());
        event.setTargetId(result.targetId());
        event.setScope(result.blockScope() != null ? result.blockScope() : FriendBlockScope.GLOBAL);
        event.setActive(result.blockActive());
        event.setExpiresAtEpochMillis(result.blockExpiresAt() != null ? result.blockExpiresAt().toEpochMilli() : null);
        event.setOwnerVersion(result.actorSnapshot() != null ? result.actorSnapshot().version() : 0L);
        event.setTargetVersion(result.targetSnapshot() != null ? result.targetSnapshot().version() : 0L);
        event.setUpdatedAtEpochMillis(result.updatedAt() != null ? result.updatedAt().toEpochMilli() : System.currentTimeMillis());
        return event;
    }

    private boolean isRequestEvent(FriendMutationType type) {
        return switch (type) {
            case INVITE_SEND, INVITE_ACCEPT, INVITE_DECLINE, INVITE_CANCEL -> true;
            default -> false;
        };
    }

    private Optional<FriendSnapshot> readCachedSnapshot(UUID playerId) {
        try {
            String payload = redisManager.sync().get(FriendRedisKeys.snapshotKey(playerId));
            if (payload == null || payload.isBlank()) {
                return Optional.empty();
            }
            FriendSnapshot snapshot = mapper.readValue(payload, FriendSnapshot.class);
            return Optional.of(snapshot);
        } catch (Exception ex) {
            logger.log(Level.FINE, "Failed to read cached friend snapshot for " + playerId, ex);
            return Optional.empty();
        }
    }

    private void cacheSnapshot(UUID playerId, FriendSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        try {
            RedisCommands<String, String> commands = redisManager.sync();
            commands.set(FriendRedisKeys.snapshotKey(playerId), mapper.writeValueAsString(snapshot));
            updateSet(commands, FriendRedisKeys.friendSetKey(playerId), snapshot.friends());
            updateSet(commands, FriendRedisKeys.outgoingKey(playerId), snapshot.outgoingRequests());
            updateSet(commands, FriendRedisKeys.incomingKey(playerId), snapshot.incomingRequests());
            for (FriendBlockScope scope : FriendBlockScope.values()) {
                updateSet(commands, FriendRedisKeys.blockedOutKey(playerId, scope), snapshot.blockedOut(scope));
                updateSet(commands, FriendRedisKeys.blockedInKey(playerId, scope), snapshot.blockedIn(scope));
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to cache friend snapshot", e);
        }
    }

    private void updateSet(RedisCommands<String, String> commands, String key, Set<UUID> members) {
        commands.del(key);
        if (members == null || members.isEmpty()) {
            return;
        }
        String[] values = members.stream().map(UUID::toString).toArray(String[]::new);
        commands.sadd(key, values);
    }

    public record FriendOperationContext(FriendOperationResult result,
                                         FriendRelationEventMessage relationEvent,
                                         FriendBlockEventMessage blockEvent) {
    }
}
