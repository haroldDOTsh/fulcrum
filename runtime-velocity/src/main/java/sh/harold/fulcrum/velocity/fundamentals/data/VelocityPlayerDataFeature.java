package sh.harold.fulcrum.velocity.fundamentals.data;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.authority.client.AuthorityCommands;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.fundamentals.messagebus.VelocityMessageBusFeature;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VelocityPlayerDataFeature implements VelocityFeature {
    private Logger logger;
    private ProxyServer proxy;
    private DataAuthority.CommandPort commandPort;
    private FulcrumVelocityPlugin plugin;
    private String proxyId = "velocity-proxy";
    private final Map<UUID, UUID> activeSessions = new ConcurrentHashMap<>();
    
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
        this.commandPort = serviceLocator.getService(DataAuthority.CommandPort.class).orElseThrow(
            () -> new RuntimeException("Data authority command port not available"));
        this.plugin = serviceLocator.getService(FulcrumVelocityPlugin.class).orElseThrow(
            () -> new RuntimeException("FulcrumVelocityPlugin not available"));
        serviceLocator.getService(VelocityMessageBusFeature.class)
            .map(VelocityMessageBusFeature::getCurrentProxyId)
            .filter(id -> id != null && !id.isBlank())
            .ifPresent(id -> this.proxyId = id);
        
        // Register event listeners - MUST use plugin instance as container
        proxy.getEventManager().register(plugin, this);
        
        logger.info("PlayerDataFeature initialized for Velocity - tracking proxy player data");
    }
    
    @Subscribe
    public void onPlayerJoin(PostLoginEvent event) {
        Player player = event.getPlayer();

        submitPlayerCommand(player, "START_SESSION");
    }
    
    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();

        submitPlayerCommand(player, "END_SESSION");
    }
    
    @Subscribe
    public void onServerSwitch(ServerPostConnectEvent event) {
        Player player = event.getPlayer();

        submitPlayerCommand(player, "RENEW_SESSION");
    }

    private void submitPlayerCommand(Player player, String declarationId) {
        long now = System.currentTimeMillis();
        UUID sessionId = sessionId(player, declarationId);
        AuthorityCommands.SessionCommands sessionCommands = AuthorityCommands.actor("velocity-proxy")
            .session(player.getUniqueId());
        String currentServer = currentServer(player);
        String lastIp = player.getRemoteAddress() != null
            ? player.getRemoteAddress().getAddress().getHostAddress()
            : null;
        int protocolVersion = player.getProtocolVersion().getProtocol();
        DataAuthority.PlayerSessionCommand command = switch (declarationId) {
            case "START_SESSION" -> sessionCommands.startSession(
                player.getUsername(),
                sessionId,
                now,
                currentServer,
                proxyId,
                lastIp,
                protocolVersion
            );
            case "RENEW_SESSION" -> sessionCommands.renewSession(
                player.getUsername(),
                sessionId,
                now,
                currentServer,
                proxyId,
                lastIp,
                protocolVersion
            );
            case "END_SESSION" -> sessionCommands.endSession(
                player.getUsername(),
                sessionId,
                now,
                currentServer,
                proxyId,
                lastIp,
                protocolVersion,
                null
            );
            default -> throw new IllegalArgumentException("Unsupported session command declaration " + declarationId);
        };

        commandPort.submit(command).whenComplete((result, error) -> {
            if (error != null) {
                logger.warn("Failed to submit {} for {}: {}", declarationId, player.getUsername(), error.getMessage());
            } else if (!result.accepted()) {
                logger.warn("Player command {} rejected for {}: {} {}", declarationId, player.getUsername(),
                    result.rejectionReason(), result.message());
            } else {
                logger.debug("Submitted {} for {}", declarationId, player.getUsername());
            }
        });
    }

    private UUID sessionId(Player player, String declarationId) {
        if ("START_SESSION".equals(declarationId)) {
            UUID sessionId = UUID.randomUUID();
            activeSessions.put(player.getUniqueId(), sessionId);
            return sessionId;
        }
        if ("END_SESSION".equals(declarationId)) {
            return activeSessions.remove(player.getUniqueId());
        }
        return activeSessions.get(player.getUniqueId());
    }

    private String currentServer(Player player) {
        return player.getCurrentServer()
            .map(server -> server.getServerInfo().getName())
            .orElse(null);
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
        return 50; // After DataAuthority (20)
    }
    
    @Override
    public String[] getDependencies() {
        return new String[] { "DataAuthority" };
    }
}
