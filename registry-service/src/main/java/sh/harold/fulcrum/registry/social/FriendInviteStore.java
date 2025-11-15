package sh.harold.fulcrum.registry.social;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lettuce.core.api.sync.RedisCommands;
import sh.harold.fulcrum.api.friends.FriendRedisKeys;
import sh.harold.fulcrum.registry.redis.RedisManager;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

public final class FriendInviteStore {

    private static final Duration INVITE_TTL = Duration.ofMinutes(5);

    private final RedisManager redisManager;
    private final ObjectMapper mapper = new ObjectMapper();

    public FriendInviteStore(RedisManager redisManager) {
        this.redisManager = Objects.requireNonNull(redisManager, "redisManager");
    }

    boolean createInvite(UUID actorId, UUID targetId, Map<String, Object> metadata) {
        RedisCommands<String, String> commands = redisManager.sync();
        String inviteKey = FriendRedisKeys.inviteKey(actorId, targetId);
        if (commands.exists(inviteKey) > 0) {
            return false;
        }
        long requestedAt = System.currentTimeMillis();
        long expiresAt = requestedAt + INVITE_TTL.toMillis();
        ObjectNode node = mapper.createObjectNode();
        node.put("actorId", actorId.toString());
        node.put("targetId", targetId.toString());
        node.put("requestedAtEpochMillis", requestedAt);
        node.put("expiresAtEpochMillis", expiresAt);
        if (metadata != null && !metadata.isEmpty()) {
            node.set("metadata", mapper.valueToTree(metadata));
        }
        try {
            commands.set(inviteKey, mapper.writeValueAsString(node));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to serialise invite payload", ex);
        }
        commands.pexpire(inviteKey, INVITE_TTL.toMillis());
        commands.sadd(FriendRedisKeys.pendingInvitesKey(targetId), actorId.toString());
        return true;
    }

    Optional<PendingInvite> consumeInvite(UUID actorId, UUID targetId) {
        RedisCommands<String, String> commands = redisManager.sync();
        String inviteKey = FriendRedisKeys.inviteKey(actorId, targetId);
        String payload = commands.get(inviteKey);
        if (payload == null) {
            commands.srem(FriendRedisKeys.pendingInvitesKey(targetId), actorId.toString());
            return Optional.empty();
        }
        commands.del(inviteKey);
        commands.srem(FriendRedisKeys.pendingInvitesKey(targetId), actorId.toString());
        return Optional.of(parseInvite(payload));
    }

    boolean hasInvite(UUID actorId, UUID targetId) {
        return redisManager.sync().exists(FriendRedisKeys.inviteKey(actorId, targetId)) > 0;
    }

    List<PendingInvite> listPendingInvites(UUID targetId) {
        RedisCommands<String, String> commands = redisManager.sync();
        Set<String> actorIds = commands.smembers(FriendRedisKeys.pendingInvitesKey(targetId));
        if (actorIds == null || actorIds.isEmpty()) {
            return List.of();
        }
        List<PendingInvite> invites = new ArrayList<>();
        for (String actorId : actorIds) {
            UUID actorUuid = UUID.fromString(actorId);
            String payload = commands.get(FriendRedisKeys.inviteKey(actorUuid, targetId));
            if (payload == null) {
                commands.srem(FriendRedisKeys.pendingInvitesKey(targetId), actorId);
                continue;
            }
            PendingInvite invite = parseInvite(payload);
            if (invite.expiresAtEpochMillis() < System.currentTimeMillis()) {
                commands.del(FriendRedisKeys.inviteKey(actorUuid, targetId));
                commands.srem(FriendRedisKeys.pendingInvitesKey(targetId), actorId);
                continue;
            }
            invites.add(invite);
        }
        invites.sort(Comparator.comparingLong(PendingInvite::requestedAtEpochMillis).reversed());
        return invites;
    }

    void cancelInvite(UUID actorId, UUID targetId) {
        RedisCommands<String, String> commands = redisManager.sync();
        commands.del(FriendRedisKeys.inviteKey(actorId, targetId));
        commands.srem(FriendRedisKeys.pendingInvitesKey(targetId), actorId.toString());
    }

    private PendingInvite parseInvite(String payload) {
        try {
            Map<String, Object> parsed = mapper.readValue(payload, Map.class);
            UUID actorId = UUID.fromString(String.valueOf(parsed.get("actorId")));
            UUID targetId = UUID.fromString(String.valueOf(parsed.get("targetId")));
            long requestedAt = ((Number) parsed.getOrDefault("requestedAtEpochMillis", 0L)).longValue();
            long expiresAt = ((Number) parsed.getOrDefault("expiresAtEpochMillis", 0L)).longValue();
            Map<String, Object> metadata = parsed.containsKey("metadata")
                    ? (Map<String, Object>) parsed.get("metadata")
                    : Map.of();
            return new PendingInvite(actorId, targetId, requestedAt, expiresAt, metadata);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to parse invite payload", ex);
        }
    }

    record PendingInvite(UUID actorId,
                         UUID targetId,
                         long requestedAtEpochMillis,
                         long expiresAtEpochMillis,
                         Map<String, Object> metadata) {
    }
}
