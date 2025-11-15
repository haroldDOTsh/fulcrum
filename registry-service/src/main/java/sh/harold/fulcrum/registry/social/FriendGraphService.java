package sh.harold.fulcrum.registry.social;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.api.sync.RedisCommands;
import sh.harold.fulcrum.api.friends.*;
import sh.harold.fulcrum.api.messagebus.messages.social.FriendBlockEventMessage;
import sh.harold.fulcrum.api.messagebus.messages.social.FriendRelationEventMessage;
import sh.harold.fulcrum.api.messagebus.messages.social.FriendRequestEventMessage;
import sh.harold.fulcrum.registry.redis.RedisManager;

import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Registry-side coordinator for friend state stored in Mongo (snapshots) and Redis (invites/cache).
 */
public final class FriendGraphService {

    private static final int BLOCK_EXPIRY_BATCH = 256;

    private final FriendSnapshotStore snapshotStore;
    private final FriendInviteStore inviteStore;
    private final RedisManager redisManager;
    private final Logger logger;
    private final ObjectMapper mapper = new ObjectMapper();

    public FriendGraphService(FriendSnapshotStore snapshotStore,
                              FriendInviteStore inviteStore,
                              RedisManager redisManager,
                              Logger logger) {
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        this.inviteStore = Objects.requireNonNull(inviteStore, "inviteStore");
        this.redisManager = Objects.requireNonNull(redisManager, "redisManager");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public FriendOperationContext apply(FriendMutationRequest request) {
        try {
            return switch (request.type()) {
                case INVITE_SEND -> handleInviteSend(request);
                case INVITE_ACCEPT -> handleInviteAccept(request);
                case INVITE_DECLINE -> handleInviteDecline(request);
                case INVITE_CANCEL -> handleInviteCancel(request);
                case UNFRIEND -> handleUnfriend(request);
                case BLOCK -> handleBlock(request);
                case UNBLOCK -> handleUnblock(request);
                default -> failure(request, "Unsupported mutation");
            };
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Failed to apply friend mutation", ex);
            return new FriendOperationContext(FriendOperationResult.failure(request.type(), ex.getMessage()), null, null);
        }
    }

    public FriendSnapshot getSnapshot(UUID playerId, boolean forceReload) {
        if (!forceReload) {
            Optional<FriendSnapshot> cached = readCachedSnapshot(playerId);
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        FriendSnapshot snapshot = snapshotStore.load(playerId);
        cacheSnapshot(playerId, snapshot);
        return snapshot;
    }

    public List<FriendBlockEventMessage> purgeExpiredBlocks() {
        RedisCommands<String, String> commands = redisManager.sync();
        long now = System.currentTimeMillis();
        List<String> expired = commands.zrangebyscore(FriendRedisKeys.blockExpiryKey(), 0, now, 0, BLOCK_EXPIRY_BATCH);
        if (expired == null || expired.isEmpty()) {
            return List.of();
        }
        commands.zrem(FriendRedisKeys.blockExpiryKey(), expired.toArray(new String[0]));
        List<FriendBlockEventMessage> events = new ArrayList<>();
        for (String entry : expired) {
            String[] parts = entry.split(":");
            if (parts.length != 3) {
                continue;
            }
            UUID owner = UUID.fromString(parts[0]);
            UUID target = UUID.fromString(parts[1]);
            FriendBlockScope scope = FriendBlockScope.values()[Integer.parseInt(parts[2])];
            events.addAll(handleExpiry(owner, target, scope));
        }
        return events;
    }

    public void shutdown() {
        snapshotStore.shutdown();
    }

    private FriendOperationContext handleInviteSend(FriendMutationRequest request) {
        UUID actor = request.actorId();
        UUID target = request.targetId();
        if (actor.equals(target)) {
            return failure(request, "You cannot add yourself");
        }
        FriendSnapshot actorSnapshot = snapshotStore.load(actor);
        FriendSnapshot targetSnapshot = snapshotStore.load(target);
        if (actorSnapshot.friends().contains(target)) {
            return failure(request, "Already friends");
        }
        if (isIgnored(actorSnapshot, target) || isIgnored(targetSnapshot, actor)) {
            return failure(request, "Cannot send request while ignored");
        }
        if (inviteStore.hasInvite(actor, target)) {
            return failure(request, "Request already pending");
        }
        if (inviteStore.hasInvite(target, actor)) {
            inviteStore.consumeInvite(target, actor);
            return commitFriendship(request, actorSnapshot, targetSnapshot);
        }
        inviteStore.createInvite(actor, target, request.metadata());
        FriendRelationEventMessage event = buildRelationEvent(actor, target,
                FriendRelationState.INVITE_OUTGOING,
                FriendRelationState.INVITE_INCOMING,
                request.type(),
                actorSnapshot.version(),
                targetSnapshot.version());
        return new FriendOperationContext(FriendOperationResult.success(request.type(), null, null), event, null);
    }

    private FriendOperationContext handleInviteAccept(FriendMutationRequest request) {
        UUID actor = request.actorId();
        UUID target = request.targetId();
        if (inviteStore.consumeInvite(target, actor).isEmpty()) {
            return failure(request, "No pending invite to accept");
        }
        FriendSnapshot actorSnapshot = snapshotStore.load(actor);
        FriendSnapshot targetSnapshot = snapshotStore.load(target);
        return commitFriendship(request, actorSnapshot, targetSnapshot);
    }

    private FriendOperationContext handleInviteDecline(FriendMutationRequest request) {
        inviteStore.consumeInvite(request.targetId(), request.actorId());
        FriendRelationEventMessage event = buildRelationEvent(
                request.actorId(),
                request.targetId(),
                null,
                null,
                request.type(),
                0L,
                0L);
        return new FriendOperationContext(FriendOperationResult.success(request.type(), null, null), event, null);
    }

    private FriendOperationContext handleInviteCancel(FriendMutationRequest request) {
        inviteStore.consumeInvite(request.actorId(), request.targetId());
        return new FriendOperationContext(FriendOperationResult.success(request.type(), null, null), null, null);
    }

    private FriendOperationContext handleUnfriend(FriendMutationRequest request) {
        UUID actor = request.actorId();
        UUID target = request.targetId();
        FriendSnapshot actorSnapshot = snapshotStore.load(actor);
        FriendSnapshot targetSnapshot = snapshotStore.load(target);
        if (!actorSnapshot.friends().contains(target)) {
            return failure(request, "Players are not friends");
        }
        FriendSnapshot updatedActor = updateFriends(actorSnapshot, set -> set.remove(target));
        FriendSnapshot updatedTarget = updateFriends(targetSnapshot, set -> set.remove(actor));
        persist(actor, updatedActor);
        persist(target, updatedTarget);
        FriendRelationEventMessage event = buildRelationEvent(actor, target,
                null, null, request.type(), updatedActor.version(), updatedTarget.version());
        return success(request, updatedActor, updatedTarget, event, null);
    }

    private FriendOperationContext handleBlock(FriendMutationRequest request) {
        UUID actor = request.actorId();
        UUID target = request.targetId();
        FriendBlockScope scope = request.scope() != null ? request.scope() : FriendBlockScope.GLOBAL;
        FriendSnapshot actorSnapshot = snapshotStore.load(actor);
        FriendSnapshot targetSnapshot = snapshotStore.load(target);
        if (actorSnapshot.blockedOut(scope).contains(target)) {
            return failure(request, "Already ignored");
        }
        inviteStore.cancelInvite(actor, target);
        inviteStore.cancelInvite(target, actor);
        FriendSnapshot cleanedActor = updateFriends(actorSnapshot, set -> set.remove(target));
        FriendSnapshot cleanedTarget = updateFriends(targetSnapshot, set -> set.remove(actor));
        FriendSnapshot updatedActor = updateIgnoresOut(cleanedActor, scope, set -> set.add(target));
        FriendSnapshot updatedTarget = updateIgnoresIn(cleanedTarget, scope, set -> set.add(actor));
        persist(actor, updatedActor);
        persist(target, updatedTarget);
        if (request.expiresAt() != null) {
            registerBlockExpiry(actor, target, scope, request.expiresAt());
        } else {
            removeBlockExpiry(actor, target, scope);
        }
        FriendBlockEventMessage event = buildBlockEvent(actor, target, updatedActor, updatedTarget, scope, true, request.expiresAt());
        return success(request, updatedActor, updatedTarget, null, event);
    }

    private FriendOperationContext handleUnblock(FriendMutationRequest request) {
        UUID actor = request.actorId();
        UUID target = request.targetId();
        FriendBlockScope scope = request.scope() != null ? request.scope() : FriendBlockScope.GLOBAL;
        FriendSnapshot actorSnapshot = snapshotStore.load(actor);
        if (!actorSnapshot.blockedOut(scope).contains(target)) {
            return failure(request, "No ignore entry to remove");
        }
        FriendSnapshot targetSnapshot = snapshotStore.load(target);
        FriendSnapshot updatedActor = updateIgnoresOut(actorSnapshot, scope, set -> set.remove(target));
        FriendSnapshot updatedTarget = updateIgnoresIn(targetSnapshot, scope, set -> set.remove(actor));
        persist(actor, updatedActor);
        persist(target, updatedTarget);
        removeBlockExpiry(actor, target, scope);
        FriendBlockEventMessage event = buildBlockEvent(actor, target, updatedActor, updatedTarget, scope, false, null);
        return success(request, updatedActor, updatedTarget, null, event);
    }

    private List<FriendBlockEventMessage> handleExpiry(UUID owner, UUID target, FriendBlockScope scope) {
        FriendSnapshot ownerSnapshot = snapshotStore.load(owner);
        if (!ownerSnapshot.blockedOut(scope).contains(target)) {
            return List.of();
        }
        FriendSnapshot targetSnapshot = snapshotStore.load(target);
        FriendSnapshot updatedOwner = updateIgnoresOut(ownerSnapshot, scope, set -> set.remove(target));
        FriendSnapshot updatedTarget = updateIgnoresIn(targetSnapshot, scope, set -> set.remove(owner));
        persist(owner, updatedOwner);
        persist(target, updatedTarget);
        FriendBlockEventMessage event = buildBlockEvent(owner, target, updatedOwner, updatedTarget, scope, false, null);
        return List.of(event);
    }

    private FriendOperationContext commitFriendship(FriendMutationRequest request,
                                                    FriendSnapshot actorSnapshot,
                                                    FriendSnapshot targetSnapshot) {
        FriendSnapshot updatedActor = updateFriends(actorSnapshot, set -> set.add(request.targetId()));
        FriendSnapshot updatedTarget = updateFriends(targetSnapshot, set -> set.add(request.actorId()));
        persist(request.actorId(), updatedActor);
        persist(request.targetId(), updatedTarget);
        FriendRelationEventMessage event = buildRelationEvent(request.actorId(), request.targetId(),
                FriendRelationState.ACCEPTED, FriendRelationState.ACCEPTED,
                FriendMutationType.INVITE_ACCEPT,
                updatedActor.version(),
                updatedTarget.version());
        return success(request, updatedActor, updatedTarget, event, null);
    }

    private boolean isIgnored(FriendSnapshot snapshot, UUID peer) {
        for (FriendBlockScope scope : FriendBlockScope.values()) {
            if (snapshot.blockedOut(scope).contains(peer)) {
                return true;
            }
        }
        return false;
    }

    private FriendOperationContext success(FriendMutationRequest request,
                                           FriendSnapshot actorSnapshot,
                                           FriendSnapshot targetSnapshot,
                                           FriendRelationEventMessage relationEvent,
                                           FriendBlockEventMessage blockEvent) {
        FriendOperationResult result = FriendOperationResult.success(request.type(), actorSnapshot, targetSnapshot);
        return new FriendOperationContext(result, relationEvent, blockEvent);
    }

    private FriendOperationContext failure(FriendMutationRequest request, String reason) {
        return new FriendOperationContext(FriendOperationResult.failure(request.type(), reason), null, null);
    }

    private FriendSnapshot updateFriends(FriendSnapshot snapshot, Consumer<Set<UUID>> mutator) {
        Set<UUID> friends = new HashSet<>(snapshot.friends());
        mutator.accept(friends);
        return new FriendSnapshot(snapshot.version() + 1, friends, snapshot.ignoresOut(), snapshot.ignoresIn());
    }

    private FriendSnapshot updateIgnoresOut(FriendSnapshot snapshot,
                                            FriendBlockScope scope,
                                            Consumer<Set<UUID>> mutator) {
        EnumMap<FriendBlockScope, Set<UUID>> map = copyScopeMap(snapshot.ignoresOut());
        mutator.accept(map.get(scope));
        return new FriendSnapshot(snapshot.version() + 1, snapshot.friends(), immutableScopeMap(map), snapshot.ignoresIn());
    }

    private FriendSnapshot updateIgnoresIn(FriendSnapshot snapshot,
                                           FriendBlockScope scope,
                                           Consumer<Set<UUID>> mutator) {
        EnumMap<FriendBlockScope, Set<UUID>> map = copyScopeMap(snapshot.ignoresIn());
        mutator.accept(map.get(scope));
        return new FriendSnapshot(snapshot.version() + 1, snapshot.friends(), snapshot.ignoresOut(), immutableScopeMap(map));
    }

    private EnumMap<FriendBlockScope, Set<UUID>> copyScopeMap(Map<FriendBlockScope, Set<UUID>> map) {
        EnumMap<FriendBlockScope, Set<UUID>> copy = new EnumMap<>(FriendBlockScope.class);
        for (FriendBlockScope scope : FriendBlockScope.values()) {
            copy.put(scope, new HashSet<>(map.getOrDefault(scope, Set.of())));
        }
        return copy;
    }

    private Map<FriendBlockScope, Set<UUID>> immutableScopeMap(EnumMap<FriendBlockScope, Set<UUID>> map) {
        EnumMap<FriendBlockScope, Set<UUID>> immutable = new EnumMap<>(FriendBlockScope.class);
        for (Map.Entry<FriendBlockScope, Set<UUID>> entry : map.entrySet()) {
            immutable.put(entry.getKey(), Collections.unmodifiableSet(new HashSet<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(immutable);
    }

    private void persist(UUID playerId, FriendSnapshot snapshot) {
        snapshotStore.save(playerId, snapshot);
        cacheSnapshot(playerId, snapshot);
    }

    private void cacheSnapshot(UUID playerId, FriendSnapshot snapshot) {
        try {
            String payload = mapper.writeValueAsString(snapshot);
            RedisCommands<String, String> commands = redisManager.sync();
            commands.set(FriendRedisKeys.snapshotKey(playerId), payload);
            updateSet(commands, FriendRedisKeys.friendSetKey(playerId), snapshot.friends());
            for (FriendBlockScope scope : FriendBlockScope.values()) {
                updateSet(commands, FriendRedisKeys.ignoresOutKey(playerId, scope), snapshot.ignoresOut().get(scope));
                updateSet(commands, FriendRedisKeys.ignoresInKey(playerId, scope), snapshot.ignoresIn().get(scope));
            }
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Failed to cache friend snapshot", ex);
        }
    }

    private Optional<FriendSnapshot> readCachedSnapshot(UUID playerId) {
        try {
            String payload = redisManager.sync().get(FriendRedisKeys.snapshotKey(playerId));
            if (payload == null || payload.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(mapper.readValue(payload, FriendSnapshot.class));
        } catch (Exception ex) {
            logger.log(Level.FINE, "Failed to read cached friend snapshot for " + playerId, ex);
            return Optional.empty();
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

    private FriendRelationEventMessage buildRelationEvent(UUID owner,
                                                          UUID peer,
                                                          FriendRelationState ownerState,
                                                          FriendRelationState peerState,
                                                          FriendMutationType type,
                                                          long ownerVersion,
                                                          long peerVersion) {
        FriendRelationEventMessage event = (type == FriendMutationType.INVITE_SEND
                || type == FriendMutationType.INVITE_ACCEPT
                || type == FriendMutationType.INVITE_DECLINE
                || type == FriendMutationType.INVITE_CANCEL)
                ? new FriendRequestEventMessage()
                : new FriendRelationEventMessage();
        event.setOwnerId(owner);
        event.setPeerId(peer);
        event.setOwnerState(ownerState);
        event.setPeerState(peerState);
        event.setMutationType(type);
        event.setOwnerVersion(ownerVersion);
        event.setPeerVersion(peerVersion);
        event.setRelationVersion(Math.max(ownerVersion, peerVersion));
        event.setUpdatedAtEpochMillis(System.currentTimeMillis());
        return event;
    }

    private FriendBlockEventMessage buildBlockEvent(UUID owner,
                                                    UUID target,
                                                    FriendSnapshot ownerSnapshot,
                                                    FriendSnapshot targetSnapshot,
                                                    FriendBlockScope scope,
                                                    boolean active,
                                                    Instant expiresAt) {
        FriendBlockEventMessage event = new FriendBlockEventMessage();
        event.setOwnerId(owner);
        event.setTargetId(target);
        event.setScope(scope);
        event.setActive(active);
        event.setExpiresAtEpochMillis(expiresAt != null ? expiresAt.toEpochMilli() : null);
        event.setOwnerVersion(ownerSnapshot != null ? ownerSnapshot.version() : 0L);
        event.setTargetVersion(targetSnapshot != null ? targetSnapshot.version() : 0L);
        event.setUpdatedAtEpochMillis(System.currentTimeMillis());
        return event;
    }

    private void registerBlockExpiry(UUID owner, UUID target, FriendBlockScope scope, Instant expiresAt) {
        if (expiresAt == null) {
            return;
        }
        redisManager.sync().zadd(FriendRedisKeys.blockExpiryKey(), expiresAt.toEpochMilli(), encodeBlockEntry(owner, target, scope));
    }

    private void removeBlockExpiry(UUID owner, UUID target, FriendBlockScope scope) {
        redisManager.sync().zrem(FriendRedisKeys.blockExpiryKey(), encodeBlockEntry(owner, target, scope));
    }

    private String encodeBlockEntry(UUID owner, UUID target, FriendBlockScope scope) {
        return owner + ":" + target + ':' + scope.ordinal();
    }

    public record FriendOperationContext(FriendOperationResult result,
                                         FriendRelationEventMessage relationEvent,
                                         FriendBlockEventMessage blockEvent) {
    }
}
