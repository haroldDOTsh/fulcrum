package sh.harold.fulcrum.registry.heartbeat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.server.ServerRegistry;
import sh.harold.fulcrum.registry.proxy.ProxyRegistry;
import sh.harold.fulcrum.registry.proxy.RegisteredProxyData;
import sh.harold.fulcrum.registry.state.RegistrationState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    
    private final ServerRegistry serverRegistry;
    private final ProxyRegistry proxyRegistry;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Long> lastHeartbeats = new ConcurrentHashMap<>();
    private final Map<String, Long> lastProxyHeartbeats = new ConcurrentHashMap<>();
    private final Map<String, Long> deadServers = new ConcurrentHashMap<>(); // Track dead servers with removal timestamp
    private final Map<String, RegisteredServerData> recentlyDeadServers = new ConcurrentHashMap<>(); // Track recently dead servers for display
    private final Map<String, RegisteredProxyData> recentlyDeadProxies = new ConcurrentHashMap<>(); // Track recently dead proxies for display
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    private ScheduledFuture<?> monitorTask;
    private Consumer<String> onServerTimeout;
    private MessageBus messageBus;
    
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
        
        // Clear any existing tracking data to start fresh
        clearTrackingData();
        
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
        // Check if this server was recently marked as dead
        Long deadTime = deadServers.get(serverId);
        if (deadTime != null) {
            long timeSinceDeath = System.currentTimeMillis() - deadTime;
            if (timeSinceDeath < DEAD_SERVER_BLACKLIST_MS) {
                // Server is blacklisted, ignore heartbeat
                LOGGER.warn("Ignoring heartbeat from dead server {} (blacklisted for {} more seconds)",
                           serverId, (DEAD_SERVER_BLACKLIST_MS - timeSinceDeath) / 1000);
                return;
            } else {
                // Blacklist period expired, remove from dead servers list
                deadServers.remove(serverId);
                LOGGER.info("Server {} blacklist period expired, allowing re-registration", serverId);
            }
        }
        
        // Check if this is a proxy heartbeat (proxy IDs contain "proxy")
        if (serverId.contains("proxy") && proxyRegistry != null) {
            // Check if it's a registered proxy first
            RegisteredProxyData proxy = proxyRegistry.getProxy(serverId);
            if (proxy != null) {
                processProxyHeartbeat(serverId);
                return;
            } else {
                LOGGER.debug("Proxy {} not found in registry", serverId);
            }
        }
        
        // Try to find the server in ServerRegistry first
        RegisteredServerData server = serverRegistry.getServer(serverId);
        if (server != null) {
            // Use the permanent ID for tracking
            String permanentId = server.getServerId();
            
            // Update tracking with permanent ID
            lastHeartbeats.put(permanentId, System.currentTimeMillis());
            
            // Also track by temp ID if this was a temp ID heartbeat
            if (!permanentId.equals(serverId)) {
                LOGGER.debug("Received heartbeat with temp ID {}, mapping to permanent ID {}",
                            serverId, permanentId);
                lastHeartbeats.put(serverId, System.currentTimeMillis());
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
        } else if (proxyRegistry != null && proxyRegistry.getProxy(serverId) != null) {
            // It's actually a proxy, process as proxy heartbeat
            processProxyHeartbeat(serverId);
        } else {
            // Just log warning about unknown node, don't request re-registration
            LOGGER.warn("Received heartbeat from unknown server/proxy: {} - ignoring (manual re-registration required)", serverId);
        }
    }
    
    /**
     * Process a heartbeat from a proxy
     * @param proxyId The proxy ID (could be temp or permanent)
     */
    private void processProxyHeartbeat(String proxyId) {
        // Check if this is a temp ID that maps to a permanent proxy
        RegisteredProxyData proxy = proxyRegistry.getProxy(proxyId);
        
        if (proxy == null && proxyId.startsWith("temp-proxy-")) {
            // Try to find proxy by checking all registered proxies
            // This handles the case where proxy sends heartbeat with temp ID before updating
            LOGGER.debug("Received heartbeat with temp ID {}, checking for matching proxy", proxyId);
            
            // Check if there's a permanent ID mapping for this temp ID
            String permanentId = proxyRegistry.getPermanentId(proxyId);
            if (permanentId != null) {
                LOGGER.info("Mapping temp ID {} to permanent ID {} for heartbeat processing",
                           proxyId, permanentId);
                proxy = proxyRegistry.getProxy(permanentId);
                if (proxy != null) {
                    // Update tracking with both IDs temporarily
                    lastProxyHeartbeats.put(permanentId, System.currentTimeMillis());
                    lastProxyHeartbeats.put(proxyId, System.currentTimeMillis());
                    
                    // Update proxy status
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
            
            // For now, just log the issue - the proxy should update its ID after receiving response
            LOGGER.debug("Proxy {} still using temporary ID for heartbeats - waiting for ID update", proxyId);
            // Still track the heartbeat to prevent false timeout
            lastProxyHeartbeats.put(proxyId, System.currentTimeMillis());
            return;
        }
        
        if (proxy != null) {
            RegistrationState currentState = proxy.getRegistrationState();
            
            // Only process heartbeats for registered proxies
            if (currentState != RegistrationState.REGISTERED) {
                LOGGER.warn("Received heartbeat from proxy {} in non-REGISTERED state: {} - ignoring",
                    proxyId, currentState);
                
                // If proxy is in RE_REGISTERING state, try to complete re-registration
                if (currentState == RegistrationState.RE_REGISTERING) {
                    boolean transitioned = proxy.transitionTo(
                        RegistrationState.REGISTERED,
                        "Heartbeat received - completing re-registration"
                    );
                    if (transitioned) {
                        LOGGER.info("Proxy {} re-registration completed (state: {} -> REGISTERED)",
                            proxyId, currentState);
                    }
                } else if (currentState == RegistrationState.DISCONNECTED) {
                    // Proxy was disconnected but is sending heartbeats again
                    boolean transitioned = proxy.transitionTo(
                        RegistrationState.RE_REGISTERING,
                        "Heartbeat received from disconnected proxy"
                    );
                    if (transitioned) {
                        // Now try to complete re-registration
                        proxy.transitionTo(
                            RegistrationState.REGISTERED,
                            "Automatic re-registration completed"
                        );
                        LOGGER.info("Proxy {} automatically re-registered after disconnect", proxyId);
                    }
                }
                // Don't update heartbeat tracking for non-registered proxies
                return;
            }
            
            lastProxyHeartbeats.put(proxyId, System.currentTimeMillis());
            
            // Also track by the proxy's actual ID in case it's different
            String actualId = proxy.getProxyIdString();
            if (!actualId.equals(proxyId)) {
                LOGGER.debug("Also tracking heartbeat for actual proxy ID: {}", actualId);
                lastProxyHeartbeats.put(actualId, System.currentTimeMillis());
            }
            
            RegisteredProxyData.Status oldStatus = proxy.getStatus();
            proxyRegistry.updateHeartbeat(proxyId);
            
            if (oldStatus != RegisteredProxyData.Status.AVAILABLE) {
                LOGGER.info("Proxy {} status changed from {} to AVAILABLE (heartbeat received, state: {})",
                           proxyId, oldStatus, currentState);
            }
            
            LOGGER.debug("Heartbeat from proxy: {} (state: {})", proxyId, currentState);
        } else {
            // Just log warning about unknown proxy, don't request re-registration
            LOGGER.warn("Received heartbeat from unknown proxy: {} - ignoring (manual re-registration required)", proxyId);
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
            
            // Remove dead proxies from tracking and save for display
            for (String proxyId : deadProxies) {
                // Save the proxy data before removal for display purposes
                RegisteredProxyData deadProxy = proxyRegistry.getProxy(proxyId);
                if (deadProxy != null) {
                    // Create a snapshot of the dead proxy
                    RegisteredProxyData snapshot = new RegisteredProxyData(
                        deadProxy.getProxyIdString(),
                        deadProxy.getAddress(),
                        deadProxy.getPort()
                    );
                    snapshot.setLastHeartbeat(deadProxy.getLastHeartbeat());
                    snapshot.setStatus(RegisteredProxyData.Status.DEAD);
                    
                    recentlyDeadProxies.put(proxyId, snapshot);
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
            }
            
            // Add to dead servers blacklist
            this.deadServers.put(serverId, System.currentTimeMillis());
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
                return true;
            }
            return false;
        });
        
        // Clean up expired dead proxies (use same timeout as servers)
        recentlyDeadProxies.entrySet().removeIf(entry -> {
            long timeSinceDeath = now - entry.getValue().getLastHeartbeat();
            if (timeSinceDeath >= DEAD_SERVER_BLACKLIST_MS) {
                LOGGER.debug("Removing proxy {} from dead proxies list (expired)", entry.getKey());
                return true;
            }
            return false;
        });
    }
    
    /**
     * Clear all tracking data (used when starting fresh)
     */
    public void clearTrackingData() {
        lastHeartbeats.clear();
        lastProxyHeartbeats.clear();
        deadServers.clear();
        recentlyDeadServers.clear();
        recentlyDeadProxies.clear();
        LOGGER.info("Cleared all heartbeat tracking data");
    }
    
    /**
     * Get recently dead servers (servers that stopped heartbeating but haven't been cleaned up yet)
     * @return Collection of recently dead servers
     */
    public Collection<RegisteredServerData> getRecentlyDeadServers() {
        return new ArrayList<>(recentlyDeadServers.values());
    }
    
    /**
     * Get recently dead proxies (proxies that stopped heartbeating but haven't been cleaned up yet)
     * @return Collection of recently dead proxies
     */
    public Collection<RegisteredProxyData> getRecentlyDeadProxies() {
        return new ArrayList<>(recentlyDeadProxies.values());
    }
    
    /**
     * Unregister a proxy from heartbeat monitoring
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