package sh.harold.fulcrum.fundamentals.messagebus;

import sh.harold.fulcrum.api.messagebus.PlayerLocator;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.messages.ProxyAnnouncementMessage;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Simple in-memory implementation of PlayerLocator with proxy tracking.
 * This is used when Redis is not available.
 */
public class SimplePlayerLocator extends PlayerLocator {
    private final Map<UUID, String> playerLocations = new ConcurrentHashMap<>();
    private final Map<String, ProxyAnnouncementMessage> knownProxies = new ConcurrentHashMap<>();
    
    public SimplePlayerLocator(MessageBus messageBus) {
        super(messageBus);
    }
    
    @Override
    public CompletableFuture<Boolean> isPlayerOnline(UUID playerId) {
        if (playerId == null) {
            return CompletableFuture.completedFuture(false);
        }
        
        // Check our local cache
        boolean isOnline = playerLocations.containsKey(playerId);
        return CompletableFuture.completedFuture(isOnline);
    }
    
    // Additional methods for tracking
    
    public String locatePlayer(UUID playerId) {
        return playerLocations.get(playerId);
    }
    
    public void updatePlayerLocation(UUID playerId, String serverId) {
        if (serverId != null) {
            playerLocations.put(playerId, serverId);
        } else {
            playerLocations.remove(playerId);
        }
    }
    
    public void removePlayer(UUID playerId) {
        playerLocations.remove(playerId);
    }
    
    // Proxy tracking methods
    
    public void registerProxy(String proxyId, ProxyAnnouncementMessage announcement) {
        knownProxies.put(proxyId, announcement);
    }
    
    public void unregisterProxy(String proxyId) {
        knownProxies.remove(proxyId);
    }
    
    public Set<String> getRegisteredProxies() {
        return knownProxies.keySet();
    }
    
    public ProxyAnnouncementMessage getProxyData(String proxyId) {
        return knownProxies.get(proxyId);
    }
}