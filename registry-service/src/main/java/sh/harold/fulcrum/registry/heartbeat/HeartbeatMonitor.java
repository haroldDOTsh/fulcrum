package sh.harold.fulcrum.registry.heartbeat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.server.ServerRegistry;

import java.util.ArrayList;
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
    
    private final ServerRegistry serverRegistry;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Long> lastHeartbeats = new ConcurrentHashMap<>();
    
    private ScheduledFuture<?> monitorTask;
    private Consumer<String> onServerTimeout;
    
    public HeartbeatMonitor(ServerRegistry serverRegistry, ScheduledExecutorService scheduler) {
        this.serverRegistry = serverRegistry;
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
        lastHeartbeats.put(serverId, System.currentTimeMillis());
        
        // Update server metrics and reset status to AVAILABLE
        RegisteredServerData server = serverRegistry.getServer(serverId);
        if (server != null) {
            RegisteredServerData.Status oldStatus = server.getStatus();
            serverRegistry.updateServerMetrics(serverId, playerCount, tps);
            server.setStatus(RegisteredServerData.Status.AVAILABLE);
            
            if (oldStatus != RegisteredServerData.Status.AVAILABLE) {
                LOGGER.info("Server {} status changed from {} to AVAILABLE (heartbeat received)",
                           serverId, oldStatus);
            }
            
            LOGGER.debug("Heartbeat from {}: {} players, {:.1f} TPS",
                        serverId, playerCount, tps);
        } else {
            LOGGER.warn("Received heartbeat from unknown server: {}", serverId);
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
     * Check for timed out servers and update their status
     */
    private void checkTimeouts() {
        long now = System.currentTimeMillis();
        List<String> deadServers = new ArrayList<>();
        
        // Check all registered servers
        for (RegisteredServerData server : serverRegistry.getAllServers()) {
            String serverId = server.getServerId();
            Long lastHeartbeat = lastHeartbeats.get(serverId);
            
            if (lastHeartbeat == null) {
                // No heartbeat recorded yet, use registration time
                lastHeartbeats.put(serverId, server.getLastHeartbeat());
                continue;
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
                        break;
                    case DEAD:
                        LOGGER.error("Server {} status changed to DEAD (no heartbeat for {}s) - removing from registry",
                                    serverId, secondsSinceHeartbeat);
                        break;
                    case AVAILABLE:
                        // This shouldn't happen in checkTimeouts, only in processHeartbeat
                        break;
                }
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
}