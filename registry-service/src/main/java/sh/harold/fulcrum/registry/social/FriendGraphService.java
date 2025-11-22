package sh.harold.fulcrum.registry.social;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

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
                case SNAPSHOT_SYNC -> handleSnapshotSync(request);
                case SET_METADATA -> handleSetMetadata(request);
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
            if (parts.length != 2) {
                continue;
            }
            UUID owner = UUID.fromString(parts[0]);
            UUID target = UUID.fromString(parts[1]);
            events.addAll(handleExpiry(owner, target));
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
        if (actorSnapshot.friendIds().contains(target)) {
            return failure(request, "Already friends");
        }
        boolean ignoreBypass = request.bypassesIgnoreChecks();
        if (!ignoreBypass && (isIgnored(actorSnapshot, target) || isIgnored(targetSnapshot, actor))) {
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
        if (inviteStore.consumeInvite(request.targetId(), request.actorId()).isEmpty()) {
            return failure(request, "No pending invite to decline");
        }
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
        if (inviteStore.consumeInvite(request.actorId(), request.targetId()).isEmpty()) {
            return failure(request, "No pending invite to cancel");
        }
        return new FriendOperationContext(FriendOperationResult.success(request.type(), null, null), null, null);
    }

    private FriendOperationContext handleUnfriend(FriendMutationRequest request) {
        UUID actor = request.actorId();
        UUID target = request.targetId();
        FriendSnapshot actorSnapshot = snapshotStore.load(actor);
        FriendSnapshot targetSnapshot = snapshotStore.load(target);
        if (!actorSnapshot.friendIds().contains(target)) {
            return failure(request, "Players are not friends");
        }
        FriendSnapshot updatedActor = updateFriends(actorSnapshot, map -> map.remove(target));
        FriendSnapshot updatedTarget = updateFriends(targetSnapshot, map -> map.remove(actor));
        persist(actor, updatedActor);
        persist(target, updatedTarget);
        FriendRelationEventMessage event = buildRelationEvent(actor, target,
                null, null, request.type(), updatedActor.version(), updatedTarget.version());
        return success(request, updatedActor, updatedTarget, event, null);
    }

    private FriendOperationContext handleBlock(FriendMutationRequest request) {
        UUID actor = request.actorId();
        UUID target = request.targetId();
        FriendSnapshot actorSnapshot = snapshotStore.load(actor);
        FriendSnapshot targetSnapshot = snapshotStore.load(target);
        if (actorSnapshot.isBlocking(target)) {
            return failure(request, "Already ignored");
        }
        inviteStore.cancelInvite(actor, target);
        inviteStore.cancelInvite(target, actor);
        FriendSnapshot cleanedActor = updateFriends(actorSnapshot, map -> map.remove(target));
        FriendSnapshot cleanedTarget = updateFriends(targetSnapshot, map -> map.remove(actor));
        Instant now = Instant.now();
        FriendSnapshot updatedActor = updateIgnoresOut(cleanedActor, map -> map.put(target, new FriendSnapshot.BlockDetails(target, now)));
        FriendSnapshot updatedTarget = updateIgnoresIn(cleanedTarget, map -> map.put(actor, new FriendSnapshot.BlockDetails(actor, now)));
        persist(actor, updatedActor);
        persist(target, updatedTarget);
        if (request.expiresAt() != null) {
            registerBlockExpiry(actor, target, request.expiresAt());
        } else {
            removeBlockExpiry(actor, target);
        }
        FriendBlockEventMessage event = buildBlockEvent(actor, target, updatedActor, updatedTarget, true, request.expiresAt());
        return success(request, updatedActor, updatedTarget, null, event);
    }

    private FriendOperationContext handleUnblock(FriendMutationRequest request) {
        UUID actor = request.actorId();
        UUID target = request.targetId();
        FriendSnapshot actorSnapshot = snapshotStore.load(actor);
        if (!actorSnapshot.isBlocking(target)) {
            return failure(request, "No ignore entry to remove");
        }
        FriendSnapshot targetSnapshot = snapshotStore.load(target);
        FriendSnapshot updatedActor = updateIgnoresOut(actorSnapshot, map -> map.remove(target));
        FriendSnapshot updatedTarget = updateIgnoresIn(targetSnapshot, map -> map.remove(actor));
        persist(actor, updatedActor);
        persist(target, updatedTarget);
        removeBlockExpiry(actor, target);
        FriendBlockEventMessage event = buildBlockEvent(actor, target, updatedActor, updatedTarget, false, null);
        return success(request, updatedActor, updatedTarget, null, event);
    }

    private FriendOperationContext handleSetMetadata(FriendMutationRequest request) {
        UUID actor = request.actorId();
        UUID target = request.targetId();
        FriendSnapshot actorSnapshot = snapshotStore.load(actor);
        if (!actorSnapshot.friendIds().contains(target)) {
            return failure(request, "Players are not friends");
        }
        String nickname = sanitizeNickname(request.metadata().get(FriendMutationRequest.METADATA_NICKNAME));
        FriendSnapshot updatedActor = updateMetadata(actorSnapshot, map -> {
            if (nickname == null) {
                map.remove(target);
            } else {
                map.put(target, new FriendSnapshot.FriendMetadata(target, nickname));
            }
        });
        persist(actor, updatedActor);
        return success(request, updatedActor, null, null, null);
    }

    private List<FriendBlockEventMessage> handleExpiry(UUID owner, UUID target) {
        FriendSnapshot ownerSnapshot = snapshotStore.load(owner);
        if (!ownerSnapshot.isBlocking(target)) {
            return List.of();
        }
        FriendSnapshot targetSnapshot = snapshotStore.load(target);
        FriendSnapshot updatedOwner = updateIgnoresOut(ownerSnapshot, set -> set.remove(target));
        FriendSnapshot updatedTarget = updateIgnoresIn(targetSnapshot, set -> set.remove(owner));
        persist(owner, updatedOwner);
        persist(target, updatedTarget);
        FriendBlockEventMessage event = buildBlockEvent(owner, target, updatedOwner, updatedTarget, false, null);
        return List.of(event);
    }

    private FriendOperationContext handleSnapshotSync(FriendMutationRequest request) {
        UUID actor = request.actorId();
        FriendSnapshot snapshot = snapshotStore.load(actor);
        cacheSnapshot(actor, snapshot);
        return success(request, snapshot, null, null, null);
    }

    private FriendOperationContext commitFriendship(FriendMutationRequest request,
                                                    FriendSnapshot actorSnapshot,
                                                    FriendSnapshot targetSnapshot) {
        Instant now = Instant.now();
        FriendSnapshot updatedActor = updateFriends(actorSnapshot,
                map -> map.put(request.targetId(), new FriendSnapshot.FriendDetails(request.targetId(), now, null)));
        FriendSnapshot updatedTarget = updateFriends(targetSnapshot,
                map -> map.put(request.actorId(), new FriendSnapshot.FriendDetails(request.actorId(), now, null)));
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
        return snapshot.isBlocking(peer);
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

    private FriendSnapshot updateFriends(FriendSnapshot snapshot,
                                         Consumer<Map<UUID, FriendSnapshot.FriendDetails>> mutator) {
        Map<UUID, FriendSnapshot.FriendDetails> friends = new LinkedHashMap<>(snapshot.friends());
        mutator.accept(friends);
        Map<UUID, FriendSnapshot.FriendMetadata> metadata = new LinkedHashMap<>(snapshot.metadata());
        metadata.keySet().retainAll(friends.keySet());
        return new FriendSnapshot(snapshot.version() + 1, friends, snapshot.ignoresOut(), snapshot.ignoresIn(), metadata);
    }

    private FriendSnapshot updateIgnoresOut(FriendSnapshot snapshot,
                                            Consumer<Map<UUID, FriendSnapshot.BlockDetails>> mutator) {
        Map<UUID, FriendSnapshot.BlockDetails> ignores = new LinkedHashMap<>(snapshot.ignoresOut());
        mutator.accept(ignores);
        return new FriendSnapshot(snapshot.version() + 1, snapshot.friends(), ignores, snapshot.ignoresIn(), snapshot.metadata());
    }

    private FriendSnapshot updateIgnoresIn(FriendSnapshot snapshot,
                                           Consumer<Map<UUID, FriendSnapshot.BlockDetails>> mutator) {
        Map<UUID, FriendSnapshot.BlockDetails> ignores = new LinkedHashMap<>(snapshot.ignoresIn());
        mutator.accept(ignores);
        return new FriendSnapshot(snapshot.version() + 1, snapshot.friends(), snapshot.ignoresOut(), ignores, snapshot.metadata());
    }

    private FriendSnapshot updateMetadata(FriendSnapshot snapshot,
                                          Consumer<Map<UUID, FriendSnapshot.FriendMetadata>> mutator) {
        Map<UUID, FriendSnapshot.FriendMetadata> metadata = new LinkedHashMap<>(snapshot.metadata());
        mutator.accept(metadata);
        metadata.keySet().retainAll(snapshot.friends().keySet());
        return new FriendSnapshot(snapshot.version() + 1, snapshot.friends(), snapshot.ignoresOut(), snapshot.ignoresIn(), metadata);
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
            updateSet(commands, FriendRedisKeys.friendSetKey(playerId), snapshot.friendIds());
            updateSet(commands, FriendRedisKeys.ignoresOutKey(playerId), snapshot.ignoresOutIds());
            updateSet(commands, FriendRedisKeys.ignoresInKey(playerId), snapshot.ignoresInIds());
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

    private void updateSet(RedisCommands<String, String> commands, String key, Collection<UUID> members) {
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
                                                    boolean active,
                                                    Instant expiresAt) {
        FriendBlockEventMessage event = new FriendBlockEventMessage();
        event.setOwnerId(owner);
        event.setTargetId(target);
        event.setActive(active);
        event.setExpiresAtEpochMillis(expiresAt != null ? expiresAt.toEpochMilli() : null);
        event.setOwnerVersion(ownerSnapshot != null ? ownerSnapshot.version() : 0L);
        event.setTargetVersion(targetSnapshot != null ? targetSnapshot.version() : 0L);
        event.setUpdatedAtEpochMillis(System.currentTimeMillis());
        return event;
    }

    private void registerBlockExpiry(UUID owner, UUID target, Instant expiresAt) {
        if (expiresAt == null) {
            return;
        }
        redisManager.sync().zadd(FriendRedisKeys.blockExpiryKey(), expiresAt.toEpochMilli(), encodeBlockEntry(owner, target));
    }

    private void removeBlockExpiry(UUID owner, UUID target) {
        redisManager.sync().zrem(FriendRedisKeys.blockExpiryKey(), encodeBlockEntry(owner, target));
    }

    private String encodeBlockEntry(UUID owner, UUID target) {
        return owner + ":" + target;
    }

    private String sanitizeNickname(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    public record FriendOperationContext(FriendOperationResult result,
                                         FriendRelationEventMessage relationEvent,
                                         FriendBlockEventMessage blockEvent) {
    }
}
