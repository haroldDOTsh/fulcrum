package sh.harold.fulcrum.registry.slot.store;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import sh.harold.fulcrum.registry.redis.RedisManager;
import sh.harold.fulcrum.registry.redis.RedisScript;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.slot.LogicalSlotRecord;

import java.util.*;

/**
 * Redis-backed persistence for slot metadata and family capacities.
 */
public class RedisSlotStore {
    private static final String SERVER_FAMILY_CAPACITY_KEY = "fulcrum:registry:servers:%s:family-capacity";
    private static final String SERVER_FAMILY_TOTAL_KEY = "fulcrum:registry:servers:%s:family-total";
    private static final String SERVER_FAMILIES_KEY = "fulcrum:registry:servers:%s:families";
    private static final String FAMILY_SERVERS_KEY = "fulcrum:registry:slots:by-family:%s";
    private static final String SLOT_HASH_KEY = "fulcrum:registry:slots:%s";

    private final RedisManager redisManager;
    private final RedisScript reserveCapacityScript;
    private final RedisScript releaseCapacityScript;

    public RedisSlotStore(RedisManager redisManager) {
        this.redisManager = Objects.requireNonNull(redisManager, "redisManager");
        this.reserveCapacityScript = redisManager.loadScriptFromResource(
                "slot-reserve-capacity",
                ScriptOutputType.INTEGER,
                "redis/scripts/slot/reserve_capacity.lua"
        );
        this.releaseCapacityScript = redisManager.loadScriptFromResource(
                "slot-release-capacity",
                ScriptOutputType.INTEGER,
                "redis/scripts/slot/release_capacity.lua"
        );
    }

    private static String capacityKey(String serverId) {
        return String.format(SERVER_FAMILY_CAPACITY_KEY, serverId);
    }

    private static String totalKey(String serverId) {
        return String.format(SERVER_FAMILY_TOTAL_KEY, serverId);
    }

    private static String familySetKey(String familyId) {
        return String.format(FAMILY_SERVERS_KEY, familyId);
    }

    private static String slotKey(String slotId) {
        return String.format(SLOT_HASH_KEY, slotId);
    }

    private static String serverFamiliesKey(String serverId) {
        return String.format(SERVER_FAMILIES_KEY, serverId);
    }

    public int reserveFamilyCapacity(String serverId, String familyId) {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(familyId, "familyId");
        String capacityKey = capacityKey(serverId);
        String familySetKey = familySetKey(familyId);
        String familiesKey = serverFamiliesKey(serverId);
        Long result = redisManager.eval(
                reserveCapacityScript,
                java.util.List.of(capacityKey, familySetKey, familiesKey),
                java.util.List.of(familyId, serverId)
        );
        return result != null ? result.intValue() : -1;
    }

    public int releaseFamilyCapacity(String serverId, String familyId) {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(familyId, "familyId");
        String capacityKey = capacityKey(serverId);
        String familySetKey = familySetKey(familyId);
        String familiesKey = serverFamiliesKey(serverId);
        Long result = redisManager.eval(
                releaseCapacityScript,
                java.util.List.of(capacityKey, familySetKey, familiesKey),
                java.util.List.of(familyId, serverId)
        );
        return result != null ? result.intValue() : 0;
    }

    public void syncServer(RegisteredServerData server) {
        if (server == null) {
            return;
        }
        RedisCommands<String, String> commands = redisManager.sync();
        String capacityKey = capacityKey(server.getServerId());
        String totalKey = totalKey(server.getServerId());
        String familiesKey = serverFamiliesKey(server.getServerId());

        Map<String, Integer> capacities = new HashMap<>(server.getSlotFamilyCapacities());

        commands.del(capacityKey);
        if (!capacities.isEmpty()) {
            capacities.forEach((family, value) ->
                    commands.hset(capacityKey, family, Integer.toString(Math.max(value, 0))));
        }

        commands.del(totalKey);
        Map<String, Integer> total = new HashMap<>(server.getSlotFamilyCapacities());
        if (!total.isEmpty()) {
            total.forEach((family, value) ->
                    commands.hset(totalKey, family, Integer.toString(value)));
        }

        Set<String> previousFamilies = commands.smembers(familiesKey);
        Set<String> newFamilies = new HashSet<>(capacities.keySet());

        for (String removedFamily : previousFamilies) {
            if (!newFamilies.contains(removedFamily)) {
                commands.srem(familySetKey(removedFamily), server.getServerId());
            }
        }

        commands.del(familiesKey);
        if (!newFamilies.isEmpty()) {
            commands.sadd(familiesKey, newFamilies.toArray(String[]::new));
        }

        for (String family : newFamilies) {
            if (capacities.getOrDefault(family, 0) > 0) {
                commands.sadd(familySetKey(family), server.getServerId());
            } else {
                commands.srem(familySetKey(family), server.getServerId());
            }
        }
    }

    public void storeSlot(RegisteredServerData server, LogicalSlotRecord slot, String familyId) {
        if (slot == null) {
            return;
        }
        RedisCommands<String, String> commands = redisManager.sync();
        String slotKey = slotKey(slot.getSlotId());

        commands.del(slotKey);
        commands.hset(slotKey, "serverId", server.getServerId());
        commands.hset(slotKey, "slotSuffix", slot.getSlotSuffix());
        if (familyId != null) {
            commands.hset(slotKey, "family", familyId);
        }
        if (slot.getStatus() != null) {
            commands.hset(slotKey, "status", slot.getStatus().name());
        }
        if (slot.getGameType() != null) {
            commands.hset(slotKey, "gameType", slot.getGameType());
        }
        commands.hset(slotKey, "maxPlayers", Integer.toString(slot.getMaxPlayers()));
        commands.hset(slotKey, "onlinePlayers", Integer.toString(slot.getOnlinePlayers()));
        commands.hset(slotKey, "lastUpdated", Long.toString(slot.getLastUpdated()));
        slot.getMetadata().forEach((key, value) -> commands.hset(slotKey, "meta:" + key, value));
        if (familyId != null && !familyId.isBlank()) {
            commands.sadd(familySetKey(familyId), slot.getSlotId());
        }
    }

    public void removeSlot(String slotId, String familyId) {
        RedisCommands<String, String> commands = redisManager.sync();
        commands.del(slotKey(slotId));
        if (familyId != null && !familyId.isBlank()) {
            commands.srem(familySetKey(familyId), slotId);
        }
    }

    public void removeServer(String serverId) {
        RedisCommands<String, String> commands = redisManager.sync();
        Set<String> families = commands.smembers(serverFamiliesKey(serverId));
        for (String family : families) {
            commands.srem(familySetKey(family), serverId);
        }
        commands.del(capacityKey(serverId));
        commands.del(totalKey(serverId));
        commands.del(serverFamiliesKey(serverId));
    }
}
