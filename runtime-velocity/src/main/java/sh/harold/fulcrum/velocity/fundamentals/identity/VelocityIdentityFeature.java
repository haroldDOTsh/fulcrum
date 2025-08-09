package sh.harold.fulcrum.velocity.fundamentals.identity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VelocityIdentityFeature implements VelocityFeature {
    
    private Logger logger;
    private ProxyServer server;
    private final Map<UUID, PlayerIdentity> identities;
    
    public VelocityIdentityFeature() {
        this.identities = new ConcurrentHashMap<>();
    }
    
    @Override
    public String getName() {
        return "Identity";
    }
    
    @Override
    public int getPriority() {
        return 200; // Highest priority - other features depend on this
    }
    
    @Override
    public boolean isFundamental() {
        return true;
    }
    
    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) throws Exception {
        this.logger = logger;
        this.server = serviceLocator.getRequiredService(ProxyServer.class);
        
        // Register event listeners
        server.getEventManager().register(this, this);
        
        logger.info("Identity feature initialized");
    }
    
    @Override
    public void shutdown() {
        server.getEventManager().unregisterListeners(this);
        identities.clear();
    }
    
    @Subscribe
    public void onPlayerLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        PlayerIdentity identity = new PlayerIdentity(
            player.getUniqueId(),
            player.getUsername(),
            System.currentTimeMillis()
        );
        identities.put(player.getUniqueId(), identity);
        logger.debug("Player {} logged in", player.getUsername());
    }
    
    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        identities.remove(player.getUniqueId());
        logger.debug("Player {} disconnected", player.getUsername());
    }
    
    @Subscribe
    public void onServerSwitch(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        PlayerIdentity identity = identities.get(player.getUniqueId());
        if (identity != null && event.getServer() != null) {
            identity.setCurrentServer(event.getServer().getServerInfo().getName());
            logger.debug("Player {} switched to server {}", 
                       player.getUsername(), event.getServer().getServerInfo().getName());
        }
    }
    
    public PlayerIdentity getIdentity(UUID playerId) {
        return identities.get(playerId);
    }
    
    public Map<UUID, PlayerIdentity> getAllIdentities() {
        return new ConcurrentHashMap<>(identities);
    }
}