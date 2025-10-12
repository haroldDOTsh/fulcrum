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

    private final Map<UUID, PlayerIdentity> identities;
    private Logger logger;
    private ProxyServer server;

    public VelocityIdentityFeature() {
        this.identities = new ConcurrentHashMap<>();
    }

    @Override
    public String getName() {
        return "Identity";
    }

    @Override
    public int getPriority() {
        return 5; // Very high priority - loads early as other features depend on this
    }

    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) throws Exception {
        this.logger = logger;
        this.server = serviceLocator.getRequiredService(ProxyServer.class);

        // Get the main plugin instance to register events
        Object plugin = serviceLocator.getRequiredService(
                Class.forName("sh.harold.fulcrum.velocity.FulcrumVelocityPlugin")
        );

        // Register event listeners using the main plugin instance
        server.getEventManager().register(plugin, this);

        logger.info("Identity feature initialized");
    }

    @Override
    public void shutdown() {
        // Note: Velocity will automatically unregister listeners when the plugin shuts down
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