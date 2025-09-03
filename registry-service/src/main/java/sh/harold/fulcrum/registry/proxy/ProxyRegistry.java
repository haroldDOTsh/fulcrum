package sh.harold.fulcrum.registry.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.harold.fulcrum.registry.allocation.IdAllocator;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Registry for managing proxy servers.
 */
public class ProxyRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyRegistry.class);
    
    // Timeout before an unavailable proxy ID can be recycled (5 minutes)
    private static final long UNAVAILABLE_PROXY_RECYCLE_TIMEOUT_MS = 5 * 60 * 1000;
    
    private final IdAllocator idAllocator;
    private final Map<String, RegisteredProxyData> proxies = new ConcurrentHashMap<>();
    private final Map<String, RegisteredProxyData> unavailableProxies = new ConcurrentHashMap<>();
    private final Map<String, Long> unavailableTimestamps = new ConcurrentHashMap<>();
    private final Map<String, String> tempIdToPermId = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor();
    private boolean debugMode = false;
    
    public ProxyRegistry(IdAllocator idAllocator) {
        this(idAllocator, false);
    }
    
    public ProxyRegistry(IdAllocator idAllocator, boolean debugMode) {
        this.idAllocator = idAllocator;
        this.debugMode = debugMode;
        startCleanupTask();
    }
    
    /**
     * Set debug mode
     * @param debugMode Enable/disable verbose logging
     */
    public void setDebugMode(boolean debugMode) {
        this.debugMode = debugMode;
    }
    
    /**
     * Register a new proxy
     * @param tempId The temporary ID from the proxy
     * @param address The proxy address
     * @param port The proxy port
     * @return The allocated permanent ID
     */
    public synchronized String registerProxy(String tempId, String address, int port) {
        // Check if this proxy is already registered (active)
        String existingId = tempIdToPermId.get(tempId);
        if (existingId != null) {
            // Check if proxy is still active
            if (proxies.containsKey(existingId)) {
                if (debugMode) {
                    LOGGER.info("Proxy already registered and active: {} -> {} (skipping duplicate registration)",
                               tempId, existingId);
                }
                return existingId;
            }
            
            // Check if this proxy was recently unavailable (prevent ID reuse)
            RegisteredProxyData unavailableProxy = unavailableProxies.get(existingId);
            if (unavailableProxy != null) {
                // Reactivate the existing proxy instead of allocating a new ID
                unavailableProxies.remove(existingId);
                unavailableTimestamps.remove(existingId);
                unavailableProxy.setStatus(RegisteredProxyData.Status.AVAILABLE);
                unavailableProxy.setLastHeartbeat(System.currentTimeMillis());
                // Note: address and port are final, can't update them
                // If proxy reconnects with different address/port, it would need a new registration
                proxies.put(existingId, unavailableProxy);
                
                LOGGER.info("Reactivated previously unavailable proxy: {} -> {} (original address: {}:{})",
                           tempId, existingId, unavailableProxy.getAddress(), unavailableProxy.getPort());
                if (!unavailableProxy.getAddress().equals(address) || unavailableProxy.getPort() != port) {
                    LOGGER.warn("Proxy {} reconnected with different address/port ({}:{} -> {}:{})",
                               existingId, unavailableProxy.getAddress(), unavailableProxy.getPort(), address, port);
                }
                return existingId;
            }
            
            // Clean up orphaned mapping
            tempIdToPermId.remove(tempId);
            LOGGER.debug("Cleaned up orphaned temp ID mapping for proxy {}", tempId);
        }
        
        // Allocate NEW contiguous proxy ID (never reuse unavailable IDs)
        String permanentId = idAllocator.allocateProxyId();
        
        // Check for ID collision (extremely rare but possible)
        if (proxies.containsKey(permanentId) || unavailableProxies.containsKey(permanentId)) {
            LOGGER.error("Proxy ID collision detected for {} - this should not happen!", permanentId);
            throw new IllegalStateException("Proxy ID collision: " + permanentId);
        }
        
        // Create proxy info
        RegisteredProxyData proxyInfo = new RegisteredProxyData(permanentId, address, port);
        
        // Register the proxy atomically
        proxies.put(permanentId, proxyInfo);
        tempIdToPermId.put(tempId, permanentId);
        
        // This is essential log - always show
        LOGGER.info("Registered proxy: {} -> {} (address: {}:{})",
                   tempId, permanentId, address, port);
        
        return permanentId;
    }
    
    /**
     * Deregister a proxy (moves to unavailable, doesn't release ID immediately)
     * Used for timeout/hung scenarios where we want to reserve the ID
     * @param proxyId The proxy ID to deregister
     */
    public synchronized void deregisterProxy(String proxyId) {
        RegisteredProxyData removed = proxies.remove(proxyId);
        if (removed != null) {
            // DO NOT release the ID immediately - move to unavailable list
            removed.setStatus(RegisteredProxyData.Status.UNAVAILABLE);
            unavailableProxies.put(proxyId, removed);
            unavailableTimestamps.put(proxyId, System.currentTimeMillis());
            
            // Keep temp ID mapping for potential reconnection
            // tempIdToPermId.values().removeIf(id -> id.equals(proxyId));
            
            LOGGER.info("Proxy {} marked as unavailable (ID reserved for reconnection)", proxyId);
        }
    }
    
    /**
     * Immediately remove a proxy and release its ID (for graceful shutdown)
     * @param proxyId The proxy ID to remove
     * @return true if the proxy was removed, false if not found
     */
    public synchronized boolean removeProxyImmediately(String proxyId) {
        RegisteredProxyData removed = proxies.remove(proxyId);
        if (removed != null) {
            // Remove temp ID mapping
            tempIdToPermId.values().removeIf(id -> id.equals(proxyId));
            
            // Immediately release the ID for reuse
            idAllocator.releaseProxyIdExplicit(proxyId, true);
            
            LOGGER.info("Proxy {} removed immediately and ID released (graceful shutdown)", proxyId);
            return true;
        }
        
        // Also check unavailable proxies
        removed = unavailableProxies.remove(proxyId);
        if (removed != null) {
            unavailableTimestamps.remove(proxyId);
            tempIdToPermId.values().removeIf(id -> id.equals(proxyId));
            idAllocator.releaseProxyIdExplicit(proxyId, true);
            
            LOGGER.info("Unavailable proxy {} removed immediately and ID released", proxyId);
            return true;
        }
        
        return false;
    }
    
    /**
     * Permanently remove a proxy and release its ID (after extended timeout)
     * @param proxyId The proxy ID to permanently remove
     */
    private synchronized void permanentlyRemoveProxy(String proxyId) {
        RegisteredProxyData removed = unavailableProxies.remove(proxyId);
        if (removed != null) {
            unavailableTimestamps.remove(proxyId);
            
            // Remove temp ID mapping
            tempIdToPermId.values().removeIf(id -> id.equals(proxyId));
            
            // Explicitly release the ID only after extended timeout
            idAllocator.releaseProxyIdExplicit(proxyId, false);
            
            LOGGER.info("Permanently removed proxy {} after timeout (ID now available for reuse)", proxyId);
        }
    }
    
    /**
     * Get proxy info by ID
     * @param proxyId The proxy ID
     * @return The proxy info, or null if not found
     */
    public RegisteredProxyData getProxy(String proxyId) {
        return proxies.get(proxyId);
    }
    
    /**
     * Get permanent ID from temporary ID
     * @param tempId The temporary ID
     * @return The permanent ID, or null if not found
     */
    public String getPermanentId(String tempId) {
        return tempIdToPermId.get(tempId);
    }
    
    /**
     * Get all registered proxies
     * @return Collection of all proxy info
     */
    public Collection<RegisteredProxyData> getAllProxies() {
        return proxies.values();
    }
    
    /**
     * Update proxy heartbeat
     * @param proxyId The proxy ID
     */
    public void updateHeartbeat(String proxyId) {
        RegisteredProxyData proxy = proxies.get(proxyId);
        if (proxy != null) {
            proxy.setLastHeartbeat(System.currentTimeMillis());
            proxy.setStatus(RegisteredProxyData.Status.AVAILABLE);
            if (debugMode) {
                LOGGER.debug("Updated heartbeat for proxy: {}", proxyId);
            }
        } else {
            if (debugMode) {
                LOGGER.warn("Received heartbeat for unregistered proxy: {}", proxyId);
            }
        }
    }
    
    /**
     * Update proxy status
     * @param proxyId The proxy ID
     * @param status The new status
     */
    public void updateProxyStatus(String proxyId, RegisteredProxyData.Status status) {
        RegisteredProxyData proxy = proxies.get(proxyId);
        if (proxy != null) {
            RegisteredProxyData.Status oldStatus = proxy.getStatus();
            if (oldStatus != status) {
                proxy.setStatus(status);
                if (debugMode) {
                    LOGGER.info("Proxy {} status changed from {} to {}", proxyId, oldStatus, status);
                }
                
                // Mark proxy as unavailable but don't release ID immediately
                if (status == RegisteredProxyData.Status.DEAD ||
                    status == RegisteredProxyData.Status.UNAVAILABLE) {
                    deregisterProxy(proxyId);
                    if (debugMode) {
                        LOGGER.info("Moved proxy {} to unavailable list (ID reserved)", proxyId);
                    }
                }
            }
        }
    }
    
    /**
     * Check if a proxy is registered
     * @param proxyId The proxy ID
     * @return true if the proxy is registered
     */
    public boolean hasProxy(String proxyId) {
        return proxies.containsKey(proxyId);
    }
    
    /**
     * Get the total number of registered proxies
     * @return The proxy count
     */
    public int getProxyCount() {
        return proxies.size();
    }
    
    /**
     * Get the total number of unavailable proxies
     * @return The unavailable proxy count
     */
    public int getUnavailableProxyCount() {
        return unavailableProxies.size();
    }
    
    /**
     * Force release an unavailable proxy ID (admin action)
     * @param proxyId The proxy ID to force release
     */
    public void forceReleaseProxyId(String proxyId) {
        if (unavailableProxies.containsKey(proxyId)) {
            permanentlyRemoveProxy(proxyId);
            LOGGER.warn("Forced release of unavailable proxy ID: {}", proxyId);
        }
    }
    
    /**
     * Start the cleanup task for unavailable proxies
     */
    private void startCleanupTask() {
        cleanupExecutor.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            unavailableTimestamps.entrySet().stream()
                .filter(entry -> now - entry.getValue() > UNAVAILABLE_PROXY_RECYCLE_TIMEOUT_MS)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(this::permanentlyRemoveProxy);
        }, 60, 60, TimeUnit.SECONDS); // Check every minute
    }
    
    /**
     * Shutdown the cleanup executor
     */
    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}