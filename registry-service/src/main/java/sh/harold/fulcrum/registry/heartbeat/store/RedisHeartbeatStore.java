package sh.harold.fulcrum.registry.heartbeat.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.api.sync.RedisCommands;
import sh.harold.fulcrum.registry.proxy.RegisteredProxyData;
import sh.harold.fulcrum.registry.redis.RedisManager;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.slot.LogicalSlotRecord;

import java.io.IOException;
import java.util.*;

/**
 * Redis-backed persistence for heartbeat timestamps and dead-node bookkeeping.
 */
public class RedisHeartbeatStore {

    private static final String SERVER_HEARTBEATS_KEY = "fulcrum:registry:heartbeat:servers";
    private static final String PROXY_HEARTBEATS_KEY = "fulcrum:registry:heartbeat:proxies";
    private static final String DEAD_SERVERS_KEY = "fulcrum:registry:dead:servers";
    private static final String DEAD_PROXIES_KEY = "fulcrum:registry:dead:proxies";
    private static final String DEAD_SERVER_SNAPSHOT_KEY = "fulcrum:registry:dead:snapshot:server:";
    private static final String DEAD_PROXY_SNAPSHOT_KEY = "fulcrum:registry:dead:snapshot:proxy:";
    private static final long SNAPSHOT_TTL_SECONDS = 60;

    private final RedisManager redisManager;
    private final ObjectMapper objectMapper;

    public RedisHeartbeatStore(RedisManager redisManager) {
        this.redisManager = Objects.requireNonNull(redisManager, "redisManager");
        this.objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    // ------ Heartbeats ------

    public Map<String, Long> loadServerHeartbeats() {
        return loadHeartbeats(SERVER_HEARTBEATS_KEY);
    }

    public Map<String, Long> loadProxyHeartbeats() {
        return loadHeartbeats(PROXY_HEARTBEATS_KEY);
    }

    private Map<String, Long> loadHeartbeats(String key) {
        RedisCommands<String, String> commands = redisManager.sync();
        List<ScoredValue<String>> values = commands.zrangeWithScores(key, 0, -1);
        Map<String, Long> result = new HashMap<>();
        for (ScoredValue<String> value : values) {
            if (value.hasValue() && value.getValue() != null) {
                result.put(value.getValue(), (long) value.getScore());
            }
        }
        return result;
    }

    public void updateServerHeartbeat(String serverId, long timestamp) {
        redisManager.sync().zadd(SERVER_HEARTBEATS_KEY, timestamp, serverId);
    }

    public void updateProxyHeartbeat(String proxyId, long timestamp) {
        redisManager.sync().zadd(PROXY_HEARTBEATS_KEY, timestamp, proxyId);
    }

    public void removeServerHeartbeat(String serverId) {
        redisManager.sync().zrem(SERVER_HEARTBEATS_KEY, serverId);
    }

    public void removeProxyHeartbeat(String proxyId) {
        redisManager.sync().zrem(PROXY_HEARTBEATS_KEY, proxyId);
    }

    // ------ Dead tracking ------

    public Map<String, Long> loadDeadServers() {
        return loadHeartbeats(DEAD_SERVERS_KEY);
    }

    public Map<String, Long> loadDeadProxies() {
        return loadHeartbeats(DEAD_PROXIES_KEY);
    }

    public void markServerDead(String serverId, long timestamp) {
        redisManager.sync().zadd(DEAD_SERVERS_KEY, timestamp, serverId);
    }

    public void markProxyDead(String proxyId, long timestamp) {
        redisManager.sync().zadd(DEAD_PROXIES_KEY, timestamp, proxyId);
    }

    public void clearDeadServer(String serverId) {
        redisManager.sync().zrem(DEAD_SERVERS_KEY, serverId);
        redisManager.sync().del(DEAD_SERVER_SNAPSHOT_KEY + serverId);
    }

    public void clearDeadProxy(String proxyId) {
        redisManager.sync().zrem(DEAD_PROXIES_KEY, proxyId);
        redisManager.sync().del(DEAD_PROXY_SNAPSHOT_KEY + proxyId);
    }

    // ------ Snapshots ------

    public void storeDeadServerSnapshot(RegisteredServerData server) {
        if (server == null) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(ServerSnapshot.from(server));
            redisManager.sync().setex(
                    DEAD_SERVER_SNAPSHOT_KEY + server.getServerId(),
                    SNAPSHOT_TTL_SECONDS,
                    payload
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize dead server snapshot for " + server.getServerId(), e);
        }
    }

    public void storeDeadProxySnapshot(RegisteredProxyData proxy) {
        if (proxy == null) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(ProxySnapshot.from(proxy));
            redisManager.sync().setex(
                    DEAD_PROXY_SNAPSHOT_KEY + proxy.getProxyIdString(),
                    SNAPSHOT_TTL_SECONDS,
                    payload
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize dead proxy snapshot for " + proxy.getProxyIdString(), e);
        }
    }

    public Optional<RegisteredServerData> loadDeadServerSnapshot(String serverId) {
        String payload = redisManager.sync().get(DEAD_SERVER_SNAPSHOT_KEY + serverId);
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(payload, ServerSnapshot.class).toDomain());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public Optional<RegisteredProxyData> loadDeadProxySnapshot(String proxyId) {
        String payload = redisManager.sync().get(DEAD_PROXY_SNAPSHOT_KEY + proxyId);
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(payload, ProxySnapshot.class).toDomain());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private record ServerSnapshot(
            String serverId,
            String tempId,
            String serverType,
            String address,
            int port,
            int maxCapacity,
            String role,
            String status,
            long lastHeartbeat,
            int playerCount,
            double tps,
            double memoryUsage,
            double cpuUsage,
            Map<String, SlotSnapshot> slots
    ) {
        static ServerSnapshot from(RegisteredServerData data) {
            Map<String, SlotSnapshot> slotSnapshots = new LinkedHashMap<>();
            for (LogicalSlotRecord record : data.getSlots()) {
                slotSnapshots.put(record.getSlotSuffix(), SlotSnapshot.from(record));
            }
            return new ServerSnapshot(
                    data.getServerId(),
                    data.getTempId(),
                    data.getServerType(),
                    data.getAddress(),
                    data.getPort(),
                    data.getMaxCapacity(),
                    data.getRole(),
                    data.getStatus().name(),
                    data.getLastHeartbeat(),
                    data.getPlayerCount(),
                    data.getTps(),
                    data.getMemoryUsage(),
                    data.getCpuUsage(),
                    slotSnapshots
            );
        }

        RegisteredServerData toDomain() {
            RegisteredServerData server = new RegisteredServerData(
                    serverId,
                    tempId,
                    serverType,
                    address,
                    port,
                    maxCapacity
            );
            if (role != null) {
                server.setRole(role);
            }
            if (status != null) {
                try {
                    server.setStatus(RegisteredServerData.Status.valueOf(status));
                } catch (IllegalArgumentException ignored) {
                }
            }
            server.setLastHeartbeat(lastHeartbeat);
            server.setPlayerCount(playerCount);
            server.setTps(tps);
            server.setMemoryUsage(memoryUsage);
            server.setCpuUsage(cpuUsage);

            if (slots != null) {
                slots.values().forEach(slotSnapshot -> slotSnapshot.applyTo(server));
            }

            return server;
        }
    }

    private record SlotSnapshot(
            String slotId,
            String slotSuffix,
            String status,
            int maxPlayers,
            int onlinePlayers,
            Map<String, String> metadata
    ) {
        static SlotSnapshot from(LogicalSlotRecord record) {
            return new SlotSnapshot(
                    record.getSlotId(),
                    record.getSlotSuffix(),
                    record.getStatus() != null ? record.getStatus().name() : null,
                    record.getMaxPlayers(),
                    record.getOnlinePlayers(),
                    new LinkedHashMap<>(record.getMetadata())
            );
        }

        void applyTo(RegisteredServerData server) {
            LogicalSlotRecord record = new LogicalSlotRecord(slotId, slotSuffix, server.getServerId());
            if (status != null) {
                try {
                    record.setStatus(Enum.valueOf(sh.harold.fulcrum.api.messagebus.messages.SlotLifecycleStatus.class, status));
                } catch (IllegalArgumentException ignored) {
                }
            }
            record.setMaxPlayers(maxPlayers);
            record.setOnlinePlayers(onlinePlayers);
            record.replaceMetadata(metadata != null ? metadata : Map.of());
            server.putSlot(record);
        }
    }

    private record ProxySnapshot(
            String proxyId,
            String address,
            int port,
            String status,
            long lastHeartbeat
    ) {
        static ProxySnapshot from(RegisteredProxyData proxy) {
            return new ProxySnapshot(
                    proxy.getProxyIdString(),
                    proxy.getAddress(),
                    proxy.getPort(),
                    proxy.getStatus().name(),
                    proxy.getLastHeartbeat()
            );
        }

        RegisteredProxyData toDomain() {
            RegisteredProxyData data = new RegisteredProxyData(ProxySnapshotHelper.parse(proxyId), address, port);
            data.setLastHeartbeat(lastHeartbeat);
            if (status != null) {
                try {
                    data.setStatus(RegisteredProxyData.Status.valueOf(status));
                } catch (IllegalArgumentException ignored) {
                }
            }
            return data;
        }
    }

    private static final class ProxySnapshotHelper {
        private ProxySnapshotHelper() {
        }

        static sh.harold.fulcrum.registry.proxy.ProxyIdentifier parse(String proxyId) {
            return sh.harold.fulcrum.registry.proxy.ProxyIdentifier.parse(proxyId);
        }
    }
}
