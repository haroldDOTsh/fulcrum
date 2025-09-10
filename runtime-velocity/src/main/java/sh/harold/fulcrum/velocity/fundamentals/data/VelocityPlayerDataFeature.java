package sh.harold.fulcrum.velocity.fundamentals.data;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;

public class VelocityPlayerDataFeature implements VelocityFeature {
    private Logger logger;
    private ProxyServer proxy;
    private DataAPI dataAPI;
    private FulcrumVelocityPlugin plugin;
    
    @Override
    public String getName() {
        return "PlayerData";
    }
    
    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) throws Exception {
        this.logger = logger;
        
        // Get required services
        this.proxy = serviceLocator.getService(ProxyServer.class).orElseThrow(
            () -> new RuntimeException("ProxyServer not available"));
        this.dataAPI = serviceLocator.getService(DataAPI.class).orElseThrow(
            () -> new RuntimeException("DataAPI not available"));
        this.plugin = serviceLocator.getService(FulcrumVelocityPlugin.class).orElseThrow(
            () -> new RuntimeException("FulcrumVelocityPlugin not available"));
        
        // Register event listeners - MUST use plugin instance as container
        proxy.getEventManager().register(plugin, this);
        
        logger.info("PlayerDataFeature initialized for Velocity - tracking proxy player data");
    }
    
    @Subscribe
    public void onPlayerJoin(PostLoginEvent event) {
        Player player = event.getPlayer();
        
        // Run async to avoid blocking
        CompletableFuture.runAsync(() -> {
            try {
                // Use unified 'players' collection
                Document playerDoc = dataAPI.collection("players").document(player.getUniqueId().toString());
                
                boolean exists = playerDoc.exists();
                
                if (!exists) {
                    // New player - create unified document
                    logger.info("Creating new player document for proxy: {} ({})", player.getUsername(), player.getUniqueId());
                    
                    // Core fields (unified)
                    playerDoc.set("uuid", player.getUniqueId().toString());
                    playerDoc.set("username", player.getUsername());
                    playerDoc.set("firstJoin", System.currentTimeMillis());
                    playerDoc.set("lastJoin", System.currentTimeMillis());
                    playerDoc.set("lastSeen", System.currentTimeMillis());
                    playerDoc.set("joinCount", 1);
                    playerDoc.set("totalPlaytime", 0L);
                    
                    // Proxy-specific fields
                    playerDoc.set("lastProxySession", System.currentTimeMillis());
                    playerDoc.set("protocolVersion", player.getProtocolVersion().getProtocol());
                } else {
                    // Existing player - update relevant fields
                    logger.info("Updating existing player document for proxy: {}", player.getUsername());
                    
                    // Update core fields
                    Integer joinCount = playerDoc.get("joinCount", 0);
                    if (joinCount == null) joinCount = 0;
                    
                    playerDoc.set("joinCount", joinCount + 1);
                    playerDoc.set("username", player.getUsername()); // Update in case of name change
                    playerDoc.set("lastJoin", System.currentTimeMillis());
                    playerDoc.set("lastSeen", System.currentTimeMillis());
                }
                
                // Update proxy-specific fields
                playerDoc.set("lastProxySession", System.currentTimeMillis());
                playerDoc.set("protocolVersion", player.getProtocolVersion().getProtocol());
                
                // Store IP if needed (unified field)
                if (player.getRemoteAddress() != null) {
                    String ip = player.getRemoteAddress().getAddress().getHostAddress();
                    playerDoc.set("lastIp", ip);
                }
                
                // Store current server if connected
                player.getCurrentServer().ifPresent(server -> {
                    playerDoc.set("currentServer", server.getServerInfo().getName());
                });
                
                logger.debug("Successfully updated player data for {}", player.getUsername());
                
            } catch (Exception e) {
                logger.warn("Failed to update player data for {}: {}", player.getUsername(), e.getMessage());
                e.printStackTrace();
            }
        });
    }
    
    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        
        // Run async to avoid blocking
        CompletableFuture.runAsync(() -> {
            try {
                Document playerDoc = dataAPI.collection("players").document(player.getUniqueId().toString());
                
                // Check if document exists before updating
                if (!playerDoc.exists()) {
                    logger.warn("Player document not found for disconnecting player: {}", player.getUsername());
                    return;
                }
                
                // Update last seen
                playerDoc.set("lastSeen", System.currentTimeMillis());
                
                // Clear current server
                playerDoc.set("currentServer", null);
                
                // Calculate session duration and update total playtime
                Long lastSession = playerDoc.get("lastProxySession", 0L);
                
                if (lastSession != null && lastSession > 0) {
                    long sessionDuration = System.currentTimeMillis() - lastSession;
                    
                    // Update total playtime (unified field)
                    Long totalPlaytime = playerDoc.get("totalPlaytime", 0L);
                    if (totalPlaytime == null) totalPlaytime = 0L;
                    
                    playerDoc.set("totalPlaytime", totalPlaytime + sessionDuration);
                    
                    logger.debug("Updated playtime for {}: session {} ms, total {} ms",
                               player.getUsername(), sessionDuration, totalPlaytime + sessionDuration);
                }
                
                // Clear current server on disconnect
                playerDoc.set("currentServer", null);
                
                logger.info("Updated disconnect data for player: {}", player.getUsername());
                
            } catch (Exception e) {
                logger.warn("Failed to update disconnect data for {}: {}", player.getUsername(), e.getMessage());
                e.printStackTrace();
            }
        });
    }
    
    @Subscribe
    public void onServerSwitch(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        
        CompletableFuture.runAsync(() -> {
            try {
                Document playerDoc = dataAPI.collection("players").document(player.getUniqueId().toString());
                
                if (!playerDoc.exists()) {
                    return;
                }
                
                // Update current server
                player.getCurrentServer().ifPresent(server -> {
                    playerDoc.set("currentServer", server.getServerInfo().getName());
                    playerDoc.set("lastServerSwitch", System.currentTimeMillis());
                    playerDoc.set("lastSeen", System.currentTimeMillis());
                });
                
            } catch (Exception e) {
                logger.warn("Failed to update server switch data for {}: {}", player.getUsername(), e.getMessage());
            }
        });
    }
    
    @Override
    public void shutdown() {
        // Unregister event listeners
        if (proxy != null && plugin != null) {
            proxy.getEventManager().unregisterListeners(plugin);
        }
        logger.info("Shutting down PlayerDataFeature for Velocity");
    }
    
    @Override
    public int getPriority() {
        return 50; // After DataAPI (20)
    }
    
    @Override
    public String[] getDependencies() {
        return new String[] { "DataAPI" };
    }
}