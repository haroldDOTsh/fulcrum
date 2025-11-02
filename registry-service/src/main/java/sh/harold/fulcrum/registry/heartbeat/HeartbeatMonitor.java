package sh.harold.fulcrum.registry.heartbeat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.registry.heartbeat.store.RedisHeartbeatStore;
import sh.harold.fulcrum.registry.proxy.ProxyRegistry;
import sh.harold.fulcrum.registry.proxy.RegisteredProxyData;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.server.ServerRegistry;
import sh.harold.fulcrum.registry.state.RegistrationState;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Monitors server heartbeats and detects timeouts.
 * Tracks three states:
 * - AVAILABLE: Heartbeat received within 5 seconds
 * - UNAVAILABLE: No heartbeat for 5-30 seconds
 * - DEAD: No heartbeat for 30+ seconds (server removed)
 */
public class HeartbeatMonitor {
    private static final Logger LOGGER = LoggerFactory.getLogger(HeartbeatMonitor.class);

    private static final long UNAVAILABLE_TIMEOUT_MS = 5000;  // 5 seconds
    private static final long DEAD_TIMEOUT_MS = 30000;        // 30 seconds
    private static final long CHECK_INTERVAL_MS = 1000;       // 1 second
    private static final long GRACE_PERIOD_MS = 10000;        // 10 second grace period for new servers
    private static final long DEAD_SERVER_BLACKLIST_MS = 60000; // 60 seconds blacklist for dead servers
    private static final long INVALID_PROXY_HEARTBEAT_LOG_INTERVAL_MS = 5000; // Rate-limit repeated warnings

    private final ServerRegistry serverRegistry;
    private final ProxyRegistry proxyRegistry;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Long> lastHeartbeats = new ConcurrentHashMap<>();
    private final Map<String, Long> lastProxyHeartbeats = new ConcurrentHashMap<>();
    private final Map<String, Long> deadServers = new ConcurrentHashMap<>(); // Track dead servers with removal timestamp
    private final Map<String, RegisteredServerData> recentlyDeadServers = new ConcurrentHashMap<>(); // Track recently dead servers for display
    private final Map<String, RegisteredProxyData> recentlyDeadProxies = new ConcurrentHashMap<>(); // Track recently dead proxies for display
    private final Map<String, Long> lastInvalidProxyHeartbeatLog = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private RedisHeartbeatStore heartbeatStore;

    private ScheduledFuture<?> monitorTask;
    private Consumer<String> onServerTimeout;
    private MessageBus messageBus;

    public HeartbeatMonitor(ServerRegistry serverRegistry, ScheduledExecutorService scheduler) {
        this(serverRegistry, null, scheduler, null);
    }

    public HeartbeatMonitor(ServerRegistry serverRegistry, ProxyRegistry proxyRegistry, ScheduledExecutorService scheduler) {
        this(serverRegistry, proxyRegistry, scheduler, null);
    }

    public HeartbeatMonitor(ServerRegistry serverRegistry, ProxyRegistry proxyRegistry, ScheduledExecutorService scheduler,
                            RedisHeartbeatStore heartbeatStore) {
        this.serverRegistry = serverRegistry;
        this.proxyRegistry = proxyRegistry;
        this.scheduler = scheduler;
        this.heartbeatStore = heartbeatStore;
    }

    /**
     * Start monitoring heartbeats
     */
    public void start() {
        if (monitorTask != null) {
            return;
        }

        loadTrackingData();

        monitorTask = scheduler.scheduleWithFixedDelay(
                this::checkTimeouts,
                CHECK_INTERVAL_MS,
                CHECK_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );

        LOGGER.info("Heartbeat monitor started (unavailable: {}s, dead: {}s, check interval: {}ms)",
                UNAVAILABLE_TIMEOUT_MS / 1000, DEAD_TIMEOUT_MS / 1000, CHECK_INTERVAL_MS);
    }

    /**
     * Stop monitoring heartbeats
     */
    public void stop() {
        if (monitorTask != null) {
            monitorTask.cancel(false);
            monitorTask = null;
            LOGGER.info("Heartbeat monitor stopped");
        }
    }

    /**
     * Process a heartbeat from a server
     *
     * @param serverId    The server ID
     * @param playerCount Player count
     * @param tps         Server TPS
     */
    public void processHeartbeat(String serverId, int playerCount, double tps) {
        if (serverId == null || serverId.isBlank()) {
            LOGGER.debug("Ignoring heartbeat missing serverId");
            return;
        }

        // Proxy heartbeats are handled separately
        if (looksLikeProxy(serverId) && proxyRegistry != null) {
            processProxyHeartbeat(serverId);
            return;
        }

        boolean restoredFromDead = attemptServerRestore(serverId);
        Long deadTime = restoredFromDead ? null : deadServers.get(serverId);
        if (deadTime != null) {
            long timeSinceDeath = System.currentTimeMillis() - deadTime;
            long remainingMs = DEAD_SERVER_BLACKLIST_MS - timeSinceDeath;
            if (remainingMs > 0) {
                LOGGER.warn("Ignoring heartbeat from dead server {} (blacklisted for {} more seconds)",
                        serverId, Math.max(1, remainingMs / 1000));
                return;
            }
            deadServers.remove(serverId);
            LOGGER.info("Server {} blacklist period expired, allowing re-registration", serverId);
        } else if (restoredFromDead) {
            recentlyDeadServers.remove(serverId);
        }

        // Try to find the server in ServerRegistry first
        RegisteredServerData server = serverRegistry.getServer(serverId);
        if (server != null) {
            String permanentId = server.getServerId();
            long now = System.currentTimeMillis();

            lastHeartbeats.put(permanentId, now);
            if (!permanentId.equals(serverId)) {
                LOGGER.debug("Received heartbeat with temp ID {}, mapping to permanent ID {}",
                        serverId, permanentId);
                lastHeartbeats.put(serverId, now);
            }
            if (heartbeatStore != null) {
                heartbeatStore.updateServerHeartbeat(permanentId, now);
                if (!permanentId.equals(serverId)) {
                    heartbeatStore.updateServerHeartbeat(serverId, now);
                }
                heartbeatStore.clearDeadServer(permanentId);
            }

            RegisteredServerData.Status oldStatus = server.getStatus();
            serverRegistry.updateServerMetrics(permanentId, playerCount, tps);
            server.setStatus(RegisteredServerData.Status.AVAILABLE);

            if (oldStatus != RegisteredServerData.Status.AVAILABLE) {
                LOGGER.info("Server {} status changed from {} to AVAILABLE (heartbeat received)",
                        permanentId, oldStatus);

                // Broadcast status change
                broadcastStatusChange(server, oldStatus, RegisteredServerData.Status.AVAILABLE);
            }

            LOGGER.debug("Heartbeat from {}: {} players, {:.1f} TPS",
                    permanentId, playerCount, tps);
        } else {
            LOGGER.warn("Received heartbeat from unknown server: {} - requesting re-registration", serverId);
            requestReregistrationForNode(serverId, "heartbeat-from-unknown-server");
        }
    }

    /**
     * Process a heartbeat from a proxy
     *
     * @param proxyId The proxy ID (could be temp or permanent)
     */
    private void processProxyHeartbeat(String proxyId) {
        String effectiveId = proxyId;
        RegisteredProxyData proxy = proxyRegistry.getProxy(proxyId);

        // Handle temp IDs that are awaiting permanent mapping
        if (proxy == null && proxyId.startsWith("temp-proxy-")) {
            LOGGER.debug("Received heartbeat with temp ID {}, checking for matching proxy", proxyId);
            String permanentId = proxyRegistry.getPermanentId(proxyId);
            if (permanentId != null) {
                effectiveId = permanentId;
                proxy = proxyRegistry.getProxy(permanentId);
                if (proxy == null) {
                    proxy = proxyRegistry.reactivateProxy(permanentId);
                    if (proxy != null) {
                        LOGGER.info("Proxy {} automatically reactivated from temp heartbeat {}", permanentId, proxyId);
                    }
                }
                if (proxy != null) {
                    recentlyDeadProxies.remove(permanentId);
                    recentlyDeadProxies.remove(proxyId);
                    long now = System.currentTimeMillis();
                    lastProxyHeartbeats.put(permanentId, now);
                    lastProxyHeartbeats.put(proxyId, now);
                    if (heartbeatStore != null) {
                        heartbeatStore.updateProxyHeartbeat(permanentId, now);
                        heartbeatStore.updateProxyHeartbeat(proxyId, now);
                        heartbeatStore.clearDeadProxy(permanentId);
                    }
                    RegisteredProxyData.Status oldStatus = proxy.getStatus();
                    proxyRegistry.updateHeartbeat(permanentId);
                    if (oldStatus != RegisteredProxyData.Status.AVAILABLE) {
                        LOGGER.info("Proxy {} status changed from {} to AVAILABLE (heartbeat via temp ID)",
                                permanentId, oldStatus);
                    }
                    LOGGER.debug("Heartbeat from proxy: {} (via temp ID: {})", permanentId, proxyId);
                    return;
                }
            }

            LOGGER.debug("Proxy {} still using temporary ID for heartbeats - waiting for ID update", proxyId);
            lastProxyHeartbeats.put(proxyId, System.currentTimeMillis());
            return;
        }

        if (proxy == null) {
            proxy = proxyRegistry.reactivateProxy(proxyId);
            if (proxy != null) {
                effectiveId = proxy.getProxyIdString();
                recentlyDeadProxies.remove(effectiveId);
                recentlyDeadProxies.remove(proxyId);
            }
        } else {
            effectiveId = proxy.getProxyIdString();
        }

        if (proxy != null) {
            if (!shouldProcessProxyHeartbeat(proxy, effectiveId, proxyId)) {
                return;
            }

            long now = System.currentTimeMillis();
            lastProxyHeartbeats.put(effectiveId, now);
            if (!effectiveId.equals(proxyId)) {
                lastProxyHeartbeats.put(proxyId, now);
            }
            if (heartbeatStore != null) {
                heartbeatStore.updateProxyHeartbeat(effectiveId, now);
                if (!effectiveId.equals(proxyId)) {
                    heartbeatStore.updateProxyHeartbeat(proxyId, now);
                }
                heartbeatStore.clearDeadProxy(effectiveId);
            }

            lastInvalidProxyHeartbeatLog.remove(effectiveId);
            lastInvalidProxyHeartbeatLog.remove(proxyId);

            RegisteredProxyData.Status oldStatus = proxy.getStatus();
            proxyRegistry.updateHeartbeat(effectiveId);

            RegistrationState updatedState = proxy.getRegistrationState();
            if (oldStatus != RegisteredProxyData.Status.AVAILABLE) {
                LOGGER.info("Proxy {} status changed from {} to AVAILABLE (state: {})",
                        effectiveId, oldStatus, updatedState);
            }

            LOGGER.debug("Heartbeat from proxy: {} (state: {})", effectiveId, updatedState);
        } else {
            LOGGER.warn("Received heartbeat from unknown proxy: {} - requesting re-registration", proxyId);
            requestReregistrationForNode(proxyId, "heartbeat-from-unknown-proxy");
        }
    }

    private boolean shouldProcessProxyHeartbeat(RegisteredProxyData proxy, String effectiveId, String rawProxyId) {
        RegistrationState currentState = proxy.getRegistrationState();

        if (currentState == RegistrationState.REGISTERED) {
            return true;
        }

        switch (currentState) {
            case REGISTERING -> {
                if (proxy.transitionTo(RegistrationState.REGISTERED,
                        "Heartbeat confirmed initial registration")) {
                    LOGGER.info("Proxy {} registration confirmed via heartbeat", effectiveId);
                }
            }
            case RE_REGISTERING -> {
                if (proxy.transitionTo(RegistrationState.REGISTERED,
                        "Heartbeat received - completing re-registration")) {
                    LOGGER.info("Proxy {} re-registration completed (state: {} -> REGISTERED)",
                            effectiveId, currentState);
                }
            }
            case DISCONNECTED -> {
                if (proxy.transitionTo(RegistrationState.RE_REGISTERING,
                        "Heartbeat received from disconnected proxy")) {
                    proxy.transitionTo(RegistrationState.REGISTERED,
                            "Automatic re-registration completed");
                    LOGGER.info("Proxy {} automatically re-registered after disconnect", effectiveId);
                }
            }
            default -> {
                handleInvalidProxyHeartbeat(effectiveId, rawProxyId, currentState);
                return false;
            }
        }

        if (proxy.getRegistrationState() != RegistrationState.REGISTERED) {
            handleInvalidProxyHeartbeat(effectiveId, rawProxyId, proxy.getRegistrationState());
            return false;
        }

        return true;
    }

    private void handleInvalidProxyHeartbeat(String effectiveId, String rawProxyId, RegistrationState state) {
        String targetId = effectiveId != null ? effectiveId : rawProxyId;
        if (targetId == null) {
            return;
        }

        if (shouldLogInvalidProxyHeartbeat(targetId)) {
            LOGGER.warn("Received heartbeat from proxy {} in state {} – ignoring until it re-registers",
                    targetId, state);
        }

        proxyRegistry.updateProxyStatus(targetId, RegisteredProxyData.Status.UNAVAILABLE);

        String reason = "heartbeat-in-" + state.name().toLowerCase(Locale.ROOT);
        if (rawProxyId != null) {
            requestReregistrationForNode(rawProxyId, reason);
        }
        if (effectiveId != null && !Objects.equals(effectiveId, rawProxyId)) {
            requestReregistrationForNode(effectiveId, reason);
        }
    }

    private boolean shouldLogInvalidProxyHeartbeat(String proxyId) {
        long now = System.currentTimeMillis();
        Long lastLogged = lastInvalidProxyHeartbeatLog.get(proxyId);
        if (lastLogged == null || now - lastLogged >= INVALID_PROXY_HEARTBEAT_LOG_INTERVAL_MS) {
            lastInvalidProxyHeartbeatLog.put(proxyId, now);
            return true;
        }
        return false;
    }

    /**
     * Set callback for when a server times out
     *
     * @param onServerTimeout The callback
     */
    public void setOnServerTimeout(Consumer<String> onServerTimeout) {
        this.onServerTimeout = onServerTimeout;
    }

    public void setHeartbeatStore(RedisHeartbeatStore heartbeatStore) {
        this.heartbeatStore = heartbeatStore;
    }

    /**
     * Check for timed out servers and proxies and update their status
     */
    private void checkTimeouts() {
        long now = System.currentTimeMillis();
        List<String> deadServersList = new ArrayList<>();
        List<String> deadProxies = new ArrayList<>();

        // Clean up expired entries from dead servers blacklist
        cleanupDeadServersBlacklist();

        // Check all registered servers
        for (RegisteredServerData server : serverRegistry.getAllServers()) {
            String serverId = server.getServerId();
            Long lastHeartbeat = lastHeartbeats.get(serverId);

            if (lastHeartbeat == null) {
                // No heartbeat recorded yet, use registration time with grace period
                long registrationTime = server.getLastHeartbeat();
                lastHeartbeats.put(serverId, registrationTime);

                // Apply grace period for newly registered servers
                long timeSinceRegistration = now - registrationTime;
                if (timeSinceRegistration < GRACE_PERIOD_MS) {
                    LOGGER.debug("Server {} is in grace period ({} ms since registration)",
                            serverId, timeSinceRegistration);
                    continue;
                }
                lastHeartbeat = registrationTime;
            }

            long timeSinceHeartbeat = now - lastHeartbeat;
            RegisteredServerData.Status oldStatus = server.getStatus();
            RegisteredServerData.Status newStatus;

            if (timeSinceHeartbeat < UNAVAILABLE_TIMEOUT_MS) {
                newStatus = RegisteredServerData.Status.AVAILABLE;
            } else if (timeSinceHeartbeat < DEAD_TIMEOUT_MS) {
                newStatus = RegisteredServerData.Status.UNAVAILABLE;
            } else {
                newStatus = RegisteredServerData.Status.DEAD;
                deadServersList.add(serverId);
            }

            if (oldStatus != newStatus) {
                server.setStatus(newStatus);
                long secondsSinceHeartbeat = timeSinceHeartbeat / 1000;

                switch (newStatus) {
                    case UNAVAILABLE:
                        LOGGER.warn("Server {} status changed to UNAVAILABLE (no heartbeat for {}s)",
                                serverId, secondsSinceHeartbeat);
                        // Broadcast status change
                        broadcastStatusChange(server, oldStatus, newStatus);
                        break;
                    case DEAD:
                        LOGGER.error("Server {} status changed to DEAD (no heartbeat for {}s) - removing from registry",
                                serverId, secondsSinceHeartbeat);
                        // Broadcast status change before removal
                        broadcastStatusChange(server, oldStatus, newStatus);
                        break;
                    case AVAILABLE:
                        // This shouldn't happen in checkTimeouts, only in processHeartbeat
                        break;
                }
            }
        }

        // Check all registered proxies if ProxyRegistry is available
        if (proxyRegistry != null) {
            for (RegisteredProxyData proxy : proxyRegistry.getAllProxies()) {
                String proxyId = proxy.getProxyIdString();
                Long lastHeartbeat = lastProxyHeartbeats.get(proxyId);

                if (lastHeartbeat == null) {
                    // No heartbeat recorded yet, use registration time with grace period
                    long registrationTime = proxy.getLastHeartbeat();
                    lastProxyHeartbeats.put(proxyId, registrationTime);

                    // Apply grace period for newly registered proxies
                    long timeSinceRegistration = now - registrationTime;
                    if (timeSinceRegistration < GRACE_PERIOD_MS) {
                        LOGGER.debug("Proxy {} is in grace period ({} ms since registration)",
                                proxyId, timeSinceRegistration);
                        continue;
                    }
                    lastHeartbeat = registrationTime;
                }

                long timeSinceHeartbeat = now - lastHeartbeat;
                RegisteredProxyData.Status oldStatus = proxy.getStatus();
                RegisteredProxyData.Status newStatus;

                if (timeSinceHeartbeat < UNAVAILABLE_TIMEOUT_MS) {
                    newStatus = RegisteredProxyData.Status.AVAILABLE;
                } else if (timeSinceHeartbeat < DEAD_TIMEOUT_MS) {
                    newStatus = RegisteredProxyData.Status.UNAVAILABLE;
                } else {
                    newStatus = RegisteredProxyData.Status.DEAD;
                    deadProxies.add(proxyId);
                }

                if (oldStatus != newStatus) {
                    long secondsSinceHeartbeat = timeSinceHeartbeat / 1000;

                    switch (newStatus) {
                        case UNAVAILABLE:
                            LOGGER.warn("Proxy {} status changed to UNAVAILABLE (no heartbeat for {}s)",
                                    proxyId, secondsSinceHeartbeat);
                            proxyRegistry.updateProxyStatus(proxyId, newStatus);
                            break;
                        case DEAD: {
                            LOGGER.error("Proxy {} status changed to DEAD (no heartbeat for {}s) - removing from registry",
                                    proxyId, secondsSinceHeartbeat);

                            RegisteredProxyData snapshot = new RegisteredProxyData(
                                    proxy.getProxyId(),
                                    proxy.getAddress(),
                                    proxy.getPort()
                            );
                            snapshot.setLastHeartbeat(proxy.getLastHeartbeat());
                            snapshot.setStatus(RegisteredProxyData.Status.DEAD);
                            recentlyDeadProxies.put(proxyId, snapshot);
                            if (heartbeatStore != null) {
                                heartbeatStore.storeDeadProxySnapshot(snapshot);
                            }

                            proxyRegistry.updateProxyStatus(proxyId, newStatus);
                            proxyRegistry.deregisterProxy(proxyId);
                            break;
                        }
                        case AVAILABLE:
                            // This shouldn't happen in checkTimeouts, only in processHeartbeat
                            break;
                    }
                }
            }

            // Remove dead proxies from tracking and ensure a snapshot exists for display
            for (String proxyId : deadProxies) {
                RegisteredProxyData deadProxy = recentlyDeadProxies.get(proxyId);
                if (deadProxy == null) {
                    RegisteredProxyData existing = proxyRegistry.getProxy(proxyId);
                    if (existing != null) {
                        deadProxy = new RegisteredProxyData(
                                existing.getProxyId(),
                                existing.getAddress(),
                                existing.getPort()
                        );
                        deadProxy.setLastHeartbeat(existing.getLastHeartbeat());
                        deadProxy.setStatus(RegisteredProxyData.Status.DEAD);
                        recentlyDeadProxies.put(proxyId, deadProxy);
                        if (heartbeatStore != null) {
                            heartbeatStore.storeDeadProxySnapshot(deadProxy);
                        }
                    }
                }

                lastProxyHeartbeats.remove(proxyId);
            }
        }

        // Remove dead servers
        for (String serverId : deadServersList) {
            lastHeartbeats.remove(serverId);

            // Save the server data before removal for display purposes
            RegisteredServerData deadServer = serverRegistry.getServer(serverId);
            if (deadServer != null) {
                // Create a snapshot of the dead server
                RegisteredServerData snapshot = new RegisteredServerData(
                        deadServer.getServerId(),
                        deadServer.getTempId(),
                        deadServer.getServerType(),
                        deadServer.getAddress(),
                        deadServer.getPort(),
                        deadServer.getMaxCapacity()
                );
                snapshot.setRole(deadServer.getRole());
                snapshot.setStatus(RegisteredServerData.Status.DEAD);
                snapshot.setLastHeartbeat(deadServer.getLastHeartbeat());
                snapshot.setPlayerCount(deadServer.getPlayerCount());
                snapshot.setTps(deadServer.getTps());

                recentlyDeadServers.put(serverId, snapshot);
                if (heartbeatStore != null) {
                    heartbeatStore.storeDeadServerSnapshot(snapshot);
                }
            }

            // Add to dead servers blacklist
            long timestamp = System.currentTimeMillis();
            this.deadServers.put(serverId, timestamp);
            if (heartbeatStore != null) {
                heartbeatStore.markServerDead(serverId, timestamp);
                heartbeatStore.removeServerHeartbeat(serverId);
            }
            LOGGER.info("Added server {} to dead servers blacklist for {} seconds",
                    serverId, DEAD_SERVER_BLACKLIST_MS / 1000);

            // Remove from registry
            serverRegistry.deregisterServer(serverId);

            // Notify callback
            if (onServerTimeout != null) {
                onServerTimeout.accept(serverId);
            }
        }
    }

    /**
     * Set MessageBus for broadcasting status changes
     */
    public void setMessageBus(MessageBus messageBus) {
        this.messageBus = messageBus;
    }

    /**
     * Broadcast server status change to all listeners
     */
    private void broadcastStatusChange(RegisteredServerData server,
                                       RegisteredServerData.Status oldStatus,
                                       RegisteredServerData.Status newStatus) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("serverId", server.getServerId());
            message.put("role", server.getRole() != null ? server.getRole() : "default");
            message.put("oldStatus", oldStatus.toString());
            message.put("newStatus", newStatus.toString());
            message.put("timestamp", System.currentTimeMillis());

            // Include current metrics
            message.put("playerCount", server.getPlayerCount());
            message.put("maxPlayers", server.getMaxCapacity());
            message.put("tps", server.getTps());

            // Use MessageBus if available
            if (messageBus != null) {
                messageBus.broadcast(ChannelConstants.REGISTRY_STATUS_CHANGE, message);
                LOGGER.debug("Broadcast status change via MessageBus for server {}: {} -> {}",
                        server.getServerId(), oldStatus, newStatus);
            } else {
                LOGGER.debug("No messaging system available, skipping status change broadcast");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to broadcast status change for server {}", server.getServerId(), e);
        }
    }

    private boolean attemptServerRestore(String serverId) {
        RegisteredServerData snapshot = recentlyDeadServers.remove(serverId);
        if (snapshot == null && heartbeatStore != null) {
            snapshot = heartbeatStore.loadDeadServerSnapshot(serverId).orElse(null);
        }
        if (snapshot == null) {
            return false;
        }

        boolean restored = serverRegistry.restoreServer(snapshot);
        if (restored) {
            deadServers.remove(serverId);
            if (heartbeatStore != null) {
                heartbeatStore.clearDeadServer(serverId);
            }
            LOGGER.info("Server {} heartbeat triggered automatic re-registration ({}:{})",
                    serverId, snapshot.getAddress(), snapshot.getPort());
        }
        return restored;
    }

    private boolean looksLikeProxy(String serverId) {
        String lower = serverId.toLowerCase(Locale.ROOT);
        return lower.startsWith("proxy-") || lower.startsWith("temp-proxy-") || lower.startsWith("fulcrum-proxy-");
    }

    private void requestReregistrationForNode(String nodeId, String reason) {
        if (messageBus == null) {
            return;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("timestamp", System.currentTimeMillis());
        payload.put("forceReregistration", true);
        payload.put("targetId", nodeId);
        payload.put("reason", reason);
        messageBus.broadcast(ChannelConstants.REGISTRY_REREGISTRATION_REQUEST, payload);
    }

    /**
     * Get the unavailable timeout in milliseconds
     */
    public long getUnavailableTimeoutMs() {
        return UNAVAILABLE_TIMEOUT_MS;
    }

    /**
     * Get the dead timeout in milliseconds
     */
    public long getDeadTimeoutMs() {
        return DEAD_TIMEOUT_MS;
    }

    /**
     * Get the grace period in milliseconds for newly registered servers
     */
    public long getGracePeriodMs() {
        return GRACE_PERIOD_MS;
    }

    /**
     * Clean up expired entries from the dead servers blacklist
     * This is called periodically during checkTimeouts
     */
    private void cleanupDeadServersBlacklist() {
        long now = System.currentTimeMillis();
        deadServers.entrySet().removeIf(entry -> {
            long timeSinceDeath = now - entry.getValue();
            if (timeSinceDeath >= DEAD_SERVER_BLACKLIST_MS) {
                LOGGER.debug("Removing server {} from dead servers blacklist (expired)", entry.getKey());
                // Also remove from recently dead servers display list
                recentlyDeadServers.remove(entry.getKey());
                if (heartbeatStore != null) {
                    heartbeatStore.clearDeadServer(entry.getKey());
                }
                return true;
            }
            return false;
        });

        // Clean up expired dead proxies (use same timeout as servers)
        recentlyDeadProxies.entrySet().removeIf(entry -> {
            long timeSinceDeath = now - entry.getValue().getLastHeartbeat();
            if (timeSinceDeath >= DEAD_SERVER_BLACKLIST_MS) {
                LOGGER.debug("Removing proxy {} from dead proxies list (expired)", entry.getKey());
                if (heartbeatStore != null) {
                    heartbeatStore.clearDeadProxy(entry.getKey());
                }
                return true;
            }
            return false;
        });
    }

    /**
     * Clear all tracking data (used when starting fresh)
     */
    private void loadTrackingData() {
        lastHeartbeats.clear();
        lastProxyHeartbeats.clear();
        deadServers.clear();
        recentlyDeadServers.clear();
        recentlyDeadProxies.clear();

        if (heartbeatStore != null) {
            lastHeartbeats.putAll(heartbeatStore.loadServerHeartbeats());
            lastProxyHeartbeats.putAll(heartbeatStore.loadProxyHeartbeats());

            Map<String, Long> storedDeadServers = heartbeatStore.loadDeadServers();
            deadServers.putAll(storedDeadServers);
            storedDeadServers.keySet().forEach(serverId ->
                    heartbeatStore.loadDeadServerSnapshot(serverId)
                            .ifPresent(snapshot -> recentlyDeadServers.put(serverId, snapshot))
            );

            heartbeatStore.loadDeadProxies().forEach((proxyId, timestamp) ->
                    heartbeatStore.loadDeadProxySnapshot(proxyId)
                            .ifPresent(snapshot -> {
                                snapshot.setStatus(RegisteredProxyData.Status.DEAD);
                                recentlyDeadProxies.put(proxyId, snapshot);
                            })
            );
        }

        LOGGER.info("Loaded heartbeat tracking state (servers={}, proxies={})",
                lastHeartbeats.size(), lastProxyHeartbeats.size());
    }

    /**
     * Get recently dead servers (servers that stopped heartbeating but haven't been cleaned up yet)
     *
     * @return Collection of recently dead servers
     */
    public Collection<RegisteredServerData> getRecentlyDeadServers() {
        return new ArrayList<>(recentlyDeadServers.values());
    }

    /**
     * Get recently dead proxies (proxies that stopped heartbeating but haven't been cleaned up yet)
     *
     * @return Collection of recently dead proxies
     */
    public Collection<RegisteredProxyData> getRecentlyDeadProxies() {
        return new ArrayList<>(recentlyDeadProxies.values());
    }

    /**
     * Unregister a proxy from heartbeat monitoring
     *
     * @param proxyId The proxy ID to unregister
     */
    public void unregisterProxy(String proxyId) {
        if (proxyId != null) {
            lastProxyHeartbeats.remove(proxyId);
            lastHeartbeats.remove(proxyId);  // Remove from both maps in case it was tracked as a server too
            LOGGER.debug("Unregistered proxy {} from heartbeat monitoring", proxyId);
        }
    }
}
