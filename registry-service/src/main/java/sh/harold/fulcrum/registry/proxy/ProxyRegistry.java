package sh.harold.fulcrum.registry.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.harold.fulcrum.registry.allocation.IdAllocator;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing proxy servers.
 */
public class ProxyRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyRegistry.class);
    
    private final IdAllocator idAllocator;
    private final Map<String, RegisteredProxyData> proxies = new ConcurrentHashMap<>();
    private final Map<String, String> tempIdToPermId = new ConcurrentHashMap<>();
    
    public ProxyRegistry(IdAllocator idAllocator) {
        this.idAllocator = idAllocator;
    }
    
    /**
     * Register a new proxy
     * @param tempId The temporary ID from the proxy
     * @param address The proxy address
     * @param port The proxy port
     * @return The allocated permanent ID
     */
    public String registerProxy(String tempId, String address, int port) {
        // Check if this proxy is already registered
        String existingId = tempIdToPermId.get(tempId);
        if (existingId != null) {
            LOGGER.info("Proxy already registered: {} -> {} (skipping duplicate registration)",
                       tempId, existingId);
            return existingId;
        }
        
        // Allocate contiguous proxy ID
        String permanentId = idAllocator.allocateProxyId();
        
        // Create proxy info
        RegisteredProxyData proxyInfo = new RegisteredProxyData(permanentId, address, port);
        
        // Register the proxy
        proxies.put(permanentId, proxyInfo);
        tempIdToPermId.put(tempId, permanentId);
        
        LOGGER.info("Registered proxy: {} -> {} (address: {}:{})",
                   tempId, permanentId, address, port);
        
        return permanentId;
    }
    
    /**
     * Deregister a proxy
     * @param proxyId The proxy ID to deregister
     */
    public void deregisterProxy(String proxyId) {
        RegisteredProxyData removed = proxies.remove(proxyId);
        if (removed != null) {
            // Release the ID for reuse
            idAllocator.releaseProxyId(proxyId);
            
            // Remove temp ID mapping
            tempIdToPermId.values().removeIf(id -> id.equals(proxyId));
            
            LOGGER.info("Deregistered proxy: {}", proxyId);
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
            LOGGER.debug("Updated heartbeat for proxy: {}", proxyId);
        } else {
            LOGGER.warn("Received heartbeat for unregistered proxy: {}", proxyId);
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
                LOGGER.info("Proxy {} status changed from {} to {}", proxyId, oldStatus, status);
                
                // Remove proxy if it's dead (ephemeral behavior)
                if (status == RegisteredProxyData.Status.DEAD) {
                    deregisterProxy(proxyId);
                    LOGGER.info("Removed dead proxy {} from registry (ephemeral)", proxyId);
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
}