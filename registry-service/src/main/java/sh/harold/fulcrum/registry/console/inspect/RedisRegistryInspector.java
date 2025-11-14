package sh.harold.fulcrum.registry.console.inspect;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.harold.fulcrum.registry.heartbeat.store.RedisHeartbeatStore;
import sh.harold.fulcrum.registry.proxy.RegisteredProxyData;
import sh.harold.fulcrum.registry.redis.RedisManager;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.server.store.RedisServerRegistryStore;
import sh.harold.fulcrum.registry.server.store.RedisServerRegistryStore.ServerDocument;
import sh.harold.fulcrum.registry.state.RegistrationState;

import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;

/**
 * Read-only projection of registry state backed directly by Redis. This allows operator tooling
 * to observe a consistent fleet view regardless of which registry instance receives the command.
 */
public class RedisRegistryInspector {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisRegistryInspector.class);

    private static final String SERVER_KEY_PREFIX = "fulcrum:registry:servers:";
    private static final String SERVER_INDEX_PREFIX = SERVER_KEY_PREFIX + "index:";
    private static final String PROXY_ACTIVE_KEY_PREFIX = "fulcrum:registry:proxies:active:";
    private static final String PROXY_UNAVAILABLE_KEY_PREFIX = "fulcrum:registry:proxies:unavailable:";
    private static final String DEAD_PROXY_SNAPSHOT_KEY_PREFIX = "fulcrum:registry:dead:snapshot:proxy:";

    private final RedisManager redisManager;
    private final RedisServerRegistryStore serverStore;
    private final RedisHeartbeatStore heartbeatStore;
    private final ObjectMapper objectMapper;

    public RedisRegistryInspector(RedisManager redisManager) {
        this.redisManager = Objects.requireNonNull(redisManager, "redisManager");
        this.serverStore = new RedisServerRegistryStore(redisManager);
        this.heartbeatStore = new RedisHeartbeatStore(redisManager);
        this.objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /**
     * Fetches all known backend servers, including recently dead entries captured by the heartbeat sweeper.
     */
    public List<ServerView> fetchServers() {
        Map<String, ServerView> views = new LinkedHashMap<>();

        Map<String, Long> heartbeats = safeRead(heartbeatStore::loadServerHeartbeats, Map.of());
        List<RegisteredServerData> activeServers = safeRead(this::loadActiveServers, List.of());
        for (RegisteredServerData server : activeServers) {
            long lastHeartbeat = heartbeats.getOrDefault(server.getServerId(), server.getLastHeartbeat());
            server.setLastHeartbeat(lastHeartbeat);
            views.put(server.getServerId(), new ServerView(server, false, 0L));
        }

        Map<String, Long> deadServers = safeRead(heartbeatStore::loadDeadServers, Map.of());
        for (Map.Entry<String, Long> entry : deadServers.entrySet()) {
            String serverId = entry.getKey();
            long deadSince = entry.getValue();

            RegisteredServerData snapshot = heartbeatStore.loadDeadServerSnapshot(serverId)
                    .map(server -> {
                        server.setStatus(RegisteredServerData.Status.DEAD);
                        long lastHeartbeat = heartbeats.getOrDefault(serverId, server.getLastHeartbeat());
                        server.setLastHeartbeat(lastHeartbeat);
                        return server;
                    })
                    .orElseGet(() -> placeholderServer(serverId, heartbeats.get(serverId)));

            views.put(serverId, new ServerView(snapshot, true, deadSince));
        }

        return new ArrayList<>(views.values());
    }

    /**
     * Fetches all known proxies, merging active, unavailable, and recently dead entries.
     */
    public List<ProxyView> fetchProxies() {
        Map<String, ProxyView> views = new LinkedHashMap<>();
        RedisCommands<String, String> commands = redisManager.sync();

        Map<String, Long> heartbeats = safeRead(heartbeatStore::loadProxyHeartbeats, Map.of());

        // Active proxies
        for (String key : commands.keys(PROXY_ACTIVE_KEY_PREFIX + "*")) {
            String payload = commands.get(key);
            if (payload == null || payload.isBlank()) {
                continue;
            }
            try {
                ProxyDocument document = objectMapper.readValue(payload, ProxyDocument.class);
                long lastHeartbeat = heartbeats.getOrDefault(document.proxyId(), document.lastHeartbeat());
                views.put(document.proxyId(), new ProxyView(
                        document.proxyId(),
                        document.address(),
                        document.port(),
                        parseStatus(document.status(), RegisteredProxyData.Status.AVAILABLE),
                        parseRegistrationState(document.registrationState()),
                        lastHeartbeat,
                        false,
                        0L,
                        null,
                        document.fulcrumVersion()
                ));
            } catch (IOException ex) {
                LOGGER.warn("Failed to parse proxy document from {}", key, ex);
            }
        }

        // Unavailable proxies
        for (String key : commands.keys(PROXY_UNAVAILABLE_KEY_PREFIX + "*")) {
            String payload = commands.get(key);
            if (payload == null || payload.isBlank()) {
                continue;
            }
            try {
                UnavailableProxyDocument document = objectMapper.readValue(payload, UnavailableProxyDocument.class);
                ProxyDocument proxy = document.proxy();
                long lastHeartbeat = heartbeats.getOrDefault(proxy.proxyId(), proxy.lastHeartbeat());
                views.put(proxy.proxyId(), new ProxyView(
                        proxy.proxyId(),
                        proxy.address(),
                        proxy.port(),
                        parseStatus(proxy.status(), RegisteredProxyData.Status.UNAVAILABLE),
                        parseRegistrationState(proxy.registrationState()),
                        lastHeartbeat,
                        false,
                        0L,
                        document.unavailableSince(),
                        proxy.fulcrumVersion()
                ));
            } catch (IOException ex) {
                LOGGER.warn("Failed to parse unavailable proxy document from {}", key, ex);
            }
        }

        // Recently dead proxies
        Map<String, Long> deadProxies = safeRead(heartbeatStore::loadDeadProxies, Map.of());
        for (Map.Entry<String, Long> entry : deadProxies.entrySet()) {
            String proxyId = entry.getKey();
            long deadSince = entry.getValue();
            String payload = commands.get(DEAD_PROXY_SNAPSHOT_KEY_PREFIX + proxyId);
            DeadProxySnapshot snapshot = null;
            if (payload != null && !payload.isBlank()) {
                try {
                    snapshot = objectMapper.readValue(payload, DeadProxySnapshot.class);
                } catch (IOException ex) {
                    LOGGER.warn("Failed to parse dead proxy snapshot for {}", proxyId, ex);
                }
            }

            RegisteredProxyData.Status status = snapshot != null
                    ? parseStatus(snapshot.status(), RegisteredProxyData.Status.DEAD)
                    : RegisteredProxyData.Status.DEAD;

            String address = snapshot != null ? snapshot.address() : "unknown";
            int port = snapshot != null ? snapshot.port() : 0;
            long lastHeartbeat = snapshot != null
                    ? snapshot.lastHeartbeat()
                    : heartbeats.getOrDefault(proxyId, 0L);

            views.put(proxyId, new ProxyView(
                    proxyId,
                    address,
                    port,
                    status,
                    RegistrationState.UNREGISTERED,
                    lastHeartbeat,
                    true,
                    deadSince,
                    null,
                    snapshot != null ? snapshot.fulcrumVersion() : null
            ));
        }

        return new ArrayList<>(views.values());
    }

    private List<RegisteredServerData> loadActiveServers() {
        try {
            return serverStore.loadAll();
        } catch (RedisCommandExecutionException ex) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Falling back to manual server scan due to Redis error", ex);
            }
            return loadActiveServersFallback();
        }
    }

    private List<RegisteredServerData> loadActiveServersFallback() {
        RedisCommands<String, String> commands = redisManager.sync();
        List<RegisteredServerData> results = new ArrayList<>();
        for (String key : commands.keys(SERVER_KEY_PREFIX + "*")) {
            String suffix = key.substring(SERVER_KEY_PREFIX.length());
            if (suffix.isEmpty() || suffix.contains(":")) {
                continue;
            }
            String payload = commands.get(key);
            if (payload == null || payload.isBlank()) {
                continue;
            }
            try {
                ServerDocument document = objectMapper.readValue(payload, ServerDocument.class);
                results.add(document.toDomain());
            } catch (IOException ex) {
                LOGGER.warn("Failed to parse server document from {}", key, ex);
            }
        }
        return results;
    }

    private RegisteredServerData placeholderServer(String serverId, Long lastHeartbeat) {
        RegisteredServerData placeholder = new RegisteredServerData(
                serverId,
                null,
                "unknown",
                "unknown",
                0,
                0
        );
        placeholder.setStatus(RegisteredServerData.Status.DEAD);
        placeholder.setLastHeartbeat(lastHeartbeat != null ? lastHeartbeat : 0L);
        return placeholder;
    }

    private RegisteredProxyData.Status parseStatus(String status, RegisteredProxyData.Status fallback) {
        if (status != null) {
            try {
                return RegisteredProxyData.Status.valueOf(status);
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        return fallback;
    }

    private RegistrationState parseRegistrationState(String state) {
        if (state != null) {
            try {
                return RegistrationState.valueOf(state);
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        return RegistrationState.UNREGISTERED;
    }

    private <T> T safeRead(Supplier<T> supplier, T fallback) {
        try {
            return supplier.get();
        } catch (Exception ex) {
            LOGGER.warn("Redis inspection read failed, returning fallback", ex);
            return fallback;
        }
    }

    public record ServerView(
            RegisteredServerData snapshot,
            boolean recentlyDead,
            long deadSince
    ) {
        public String serverId() {
            return snapshot.getServerId();
        }

        public RegisteredServerData.Status status() {
            return snapshot.getStatus();
        }

        public long lastHeartbeat() {
            return snapshot.getLastHeartbeat();
        }
    }

    public record ProxyView(
            String proxyId,
            String address,
            int port,
            RegisteredProxyData.Status status,
            RegistrationState registrationState,
            long lastHeartbeat,
            boolean recentlyDead,
            long deadSince,
            Long unavailableSince,
            String fulcrumVersion
    ) {
    }

    private record ProxyDocument(
            String proxyId,
            String address,
            int port,
            String status,
            long lastHeartbeat,
            String registrationState,
            String fulcrumVersion
    ) {
    }

    private record UnavailableProxyDocument(
            ProxyDocument proxy,
            long unavailableSince
    ) {
    }

    private record DeadProxySnapshot(
            String proxyId,
            String address,
            int port,
            String status,
            long lastHeartbeat,
            String fulcrumVersion
    ) {
    }
}
