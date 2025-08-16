package sh.harold.fulcrum.registry.heartbeat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.harold.fulcrum.registry.messagebus.RegistryMessageBus;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.server.ServerRegistry;
import sh.harold.fulcrum.registry.proxy.ProxyRegistry;
import sh.harold.fulcrum.registry.proxy.RegisteredProxyData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    
    private final ServerRegistry serverRegistry;
    private final ProxyRegistry proxyRegistry;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Long> lastHeartbeats = new ConcurrentHashMap<>();
    private final Map<String, Long> lastProxyHeartbeats = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    private ScheduledFuture<?> monitorTask;
    private Consumer<String> onServerTimeout;
    private RedisCommands<String, String> redisCommands;
    private RegistryMessageBus messageBus;
    
    public HeartbeatMonitor(ServerRegistry serverRegistry, ScheduledExecutorService scheduler) {
        this(serverRegistry, null, scheduler);
    }
    
    public HeartbeatMonitor(ServerRegistry serverRegistry, ProxyRegistry proxyRegistry, ScheduledExecutorService scheduler) {
        this.serverRegistry = serverRegistry;
        this.proxyRegistry = proxyRegistry;
        this.scheduler = scheduler;
    }
    
    /**
     * Start monitoring heartbeats
     */
    public void start() {
        if (monitorTask != null) {
            return;
        }
        
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
     * @param serverId The server ID
     * @param playerCount Player count
     * @param tps Server TPS
     */
    public void processHeartbeat(String serverId, int playerCount, double tps) {
        // Check if this is a proxy heartbeat (proxy IDs contain "proxy")
        if (serverId.contains("proxy") && proxyRegistry != null) {
            // Check if it's a registered proxy first
            RegisteredProxyData proxy = proxyRegistry.getProxy(serverId);
            if (proxy != null) {
                processProxyHeartbeat(serverId);
                return;
            }
        }
        
        // Try to find the server in ServerRegistry first
        RegisteredServerData server = serverRegistry.getServer(serverId);
        if (server != null) {
            lastHeartbeats.put(serverId, System.currentTimeMillis());
            
            RegisteredServerData.Status oldStatus = server.getStatus();
            serverRegistry.updateServerMetrics(serverId, playerCount, tps);
            server.setStatus(RegisteredServerData.Status.AVAILABLE);
            
            if (oldStatus != RegisteredServerData.Status.AVAILABLE) {
                LOGGER.info("Server {} status changed from {} to AVAILABLE (heartbeat received)",
                           serverId, oldStatus);
                
                // Broadcast status change
                broadcastStatusChange(server, oldStatus, RegisteredServerData.Status.AVAILABLE);
            }
            
            LOGGER.debug("Heartbeat from {}: {} players, {:.1f} TPS",
                        serverId, playerCount, tps);
        } else if (proxyRegistry != null && proxyRegistry.getProxy(serverId) != null) {
            // It's actually a proxy, process as proxy heartbeat
            processProxyHeartbeat(serverId);
        } else {
            LOGGER.warn("Received heartbeat from unknown server/proxy: {}", serverId);
        }
    }
    
    /**
     * Process a heartbeat from a proxy
     * @param proxyId The proxy ID
     */
    private void processProxyHeartbeat(String proxyId) {
        lastProxyHeartbeats.put(proxyId, System.currentTimeMillis());
        
        RegisteredProxyData proxy = proxyRegistry.getProxy(proxyId);
        if (proxy != null) {
            RegisteredProxyData.Status oldStatus = proxy.getStatus();
            proxyRegistry.updateHeartbeat(proxyId);
            
            if (oldStatus != RegisteredProxyData.Status.AVAILABLE) {
                LOGGER.info("Proxy {} status changed from {} to AVAILABLE (heartbeat received)",
                           proxyId, oldStatus);
            }
            
            LOGGER.debug("Heartbeat from proxy: {}", proxyId);
        } else {
            LOGGER.warn("Received heartbeat from unknown proxy: {}", proxyId);
        }
    }
    
    /**
     * Set callback for when a server times out
     * @param onServerTimeout The callback
     */
    public void setOnServerTimeout(Consumer<String> onServerTimeout) {
        this.onServerTimeout = onServerTimeout;
    }
    
    /**
     * Check for timed out servers and proxies and update their status
     */
    private void checkTimeouts() {
        long now = System.currentTimeMillis();
        List<String> deadServers = new ArrayList<>();
        List<String> deadProxies = new ArrayList<>();
        
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
                deadServers.add(serverId);
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
                String proxyId = proxy.getProxyId();
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
                        case DEAD:
                            LOGGER.error("Proxy {} status changed to DEAD (no heartbeat for {}s) - removing from registry",
                                        proxyId, secondsSinceHeartbeat);
                            // ProxyRegistry handles removal automatically when status is set to DEAD
                            proxyRegistry.updateProxyStatus(proxyId, newStatus);
                            break;
                        case AVAILABLE:
                            // This shouldn't happen in checkTimeouts, only in processHeartbeat
                            break;
                    }
                }
            }
            
            // Remove dead proxies from tracking
            for (String proxyId : deadProxies) {
                lastProxyHeartbeats.remove(proxyId);
            }
        }
        
        // Remove dead servers
        for (String serverId : deadServers) {
            lastHeartbeats.remove(serverId);
            serverRegistry.deregisterServer(serverId);
            
            // Notify callback
            if (onServerTimeout != null) {
                onServerTimeout.accept(serverId);
            }
        }
    }
    
    /**
     * Set Redis commands for broadcasting status changes
     */
    public void setRedisCommands(RedisCommands<String, String> redisCommands) {
        this.redisCommands = redisCommands;
    }
    
    /**
     * Set MessageBus for broadcasting status changes
     */
    public void setMessageBus(RegistryMessageBus messageBus) {
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
            message.put("serverFamily", server.getRole() != null ? server.getRole() : "default");
            message.put("oldStatus", oldStatus.toString());
            message.put("newStatus", newStatus.toString());
            message.put("timestamp", System.currentTimeMillis());
            
            // Include current metrics
            message.put("playerCount", server.getPlayerCount());
            message.put("maxPlayers", server.getMaxCapacity());
            message.put("tps", server.getTps());
            
            // Use MessageBus if available, otherwise fall back to direct Redis
            if (messageBus != null) {
                messageBus.broadcast("registry:status:change", message);
                LOGGER.debug("Broadcast status change via MessageBus for server {}: {} -> {}",
                            server.getServerId(), oldStatus, newStatus);
            } else if (redisCommands != null) {
                String json = objectMapper.writeValueAsString(message);
                redisCommands.publish("registry:status:change", json);
                LOGGER.debug("Broadcast status change via Redis for server {}: {} -> {}",
                            server.getServerId(), oldStatus, newStatus);
            } else {
                LOGGER.debug("No messaging system available, skipping status change broadcast");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to broadcast status change for server {}", server.getServerId(), e);
        }
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
}