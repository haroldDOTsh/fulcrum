package sh.harold.fulcrum.registry.heartbeat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.harold.fulcrum.registry.server.ServerRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Monitors server heartbeats and detects timeouts.
 */
public class HeartbeatMonitor {
    private static final Logger LOGGER = LoggerFactory.getLogger(HeartbeatMonitor.class);
    
    private static final long HEARTBEAT_TIMEOUT_MS = 15000; // 15 seconds
    private static final long CHECK_INTERVAL_MS = 5000; // 5 seconds
    
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
        
        LOGGER.info("Heartbeat monitor started (timeout: {}ms, check interval: {}ms)", 
                   HEARTBEAT_TIMEOUT_MS, CHECK_INTERVAL_MS);
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
        
        // Update server metrics
        serverRegistry.updateServerMetrics(serverId, playerCount, tps);
        
        LOGGER.debug("Heartbeat from {}: {} players, {:.1f} TPS", 
                    serverId, playerCount, tps);
    }
    
    /**
     * Set callback for when a server times out
     * @param onServerTimeout The callback
     */
    public void setOnServerTimeout(Consumer<String> onServerTimeout) {
        this.onServerTimeout = onServerTimeout;
    }
    
    /**
     * Check for timed out servers
     */
    private void checkTimeouts() {
        long now = System.currentTimeMillis();
        
        lastHeartbeats.entrySet().removeIf(entry -> {
            String serverId = entry.getKey();
            long lastHeartbeat = entry.getValue();
            
            if (now - lastHeartbeat > HEARTBEAT_TIMEOUT_MS) {
                LOGGER.warn("Server {} timed out (no heartbeat for {}ms)", 
                           serverId, now - lastHeartbeat);
                
                // Deregister the server
                serverRegistry.deregisterServer(serverId);
                
                // Notify callback
                if (onServerTimeout != null) {
                    onServerTimeout.accept(serverId);
                }
                
                return true; // Remove from map
            }
            
            return false;
        });
    }
    
    /**
     * Get the heartbeat timeout in milliseconds
     */
    public long getHeartbeatTimeoutMs() {
        return HEARTBEAT_TIMEOUT_MS;
    }
}