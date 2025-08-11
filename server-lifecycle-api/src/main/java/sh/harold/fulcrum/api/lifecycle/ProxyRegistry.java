package sh.harold.fulcrum.api.lifecycle;

import sh.harold.fulcrum.api.messagebus.messages.ProxyAnnouncementMessage;

import java.util.Set;

/**
 * Registry for tracking available proxies in the network.
 * This is separate from the message bus to maintain architectural purity.
 * Implementations can use Redis, in-memory storage, or other backends.
 */
public interface ProxyRegistry {
    
    /**
     * Register a proxy with its announcement data
     * @param proxyId The unique proxy identifier
     * @param announcement The proxy announcement containing metadata
     */
    void registerProxy(String proxyId, ProxyAnnouncementMessage announcement);
    
    /**
     * Unregister a proxy
     * @param proxyId The proxy to remove
     */
    void unregisterProxy(String proxyId);
    
    /**
     * Get all registered proxy IDs
     * @return Set of proxy identifiers
     */
    Set<String> getRegisteredProxies();
    
    /**
     * Get announcement data for a specific proxy
     * @param proxyId The proxy to query
     * @return ProxyAnnouncementMessage or null if not found
     */
    ProxyAnnouncementMessage getProxyData(String proxyId);
    
    /**
     * Refresh the TTL for a proxy (if applicable)
     * @param proxyId The proxy to refresh
     * @param announcement Updated announcement data
     */
    void refreshProxyTTL(String proxyId, ProxyAnnouncementMessage announcement);
    
    /**
     * Select the best available proxy based on load
     * @return Proxy ID of the best proxy, or null if none available
     */
    default String selectBestProxy() {
        String bestProxy = null;
        double lowestLoad = 100.0;
        
        for (String proxyId : getRegisteredProxies()) {
            ProxyAnnouncementMessage proxy = getProxyData(proxyId);
            if (proxy != null && proxy.hasCapacity() && proxy.getLoadPercentage() < lowestLoad) {
                lowestLoad = proxy.getLoadPercentage();
                bestProxy = proxyId;
            }
        }
        
        return bestProxy;
    }
}