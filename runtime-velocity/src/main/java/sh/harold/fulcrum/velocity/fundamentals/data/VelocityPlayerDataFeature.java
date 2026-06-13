package sh.harold.fulcrum.velocity.fundamentals.data;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.fundamentals.messagebus.VelocityMessageBusFeature;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
        
        // Run async to avoid blocking
        CompletableFuture.runAsync(() -> submitPlayerCommand(player, DataAuthority.CommandType.START_SESSION, true));
    }
    
    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        
        // Run async to avoid blocking
        CompletableFuture.runAsync(() -> submitPlayerCommand(player, DataAuthority.CommandType.END_SESSION, false));
    }
    
    @Subscribe
    public void onServerSwitch(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        
        CompletableFuture.runAsync(() -> submitPlayerCommand(player, DataAuthority.CommandType.RENEW_SESSION, true));
    }

    private void submitPlayerCommand(Player player, DataAuthority.CommandType commandType, boolean online) {
        long now = System.currentTimeMillis();
        DataAuthority.CommandEnvelope command = new DataAuthority.CommandEnvelope(
            UUID.randomUUID(),
            commandType,
            "velocity-proxy",
            "player:" + player.getUniqueId(),
            commandType.name() + ":" + player.getUniqueId() + ":" + now,
            now + 5000L,
            "",
            0L,
            playerPayload(player, commandType, online, now)
        );

        commandPort.submit(command).whenComplete((result, error) -> {
            if (error != null) {
                logger.warn("Failed to submit {} for {}: {}", commandType, player.getUsername(), error.getMessage());
            } else if (!result.accepted()) {
                logger.warn("Player command {} rejected for {}: {} {}", commandType, player.getUsername(),
                    result.rejectionReason(), result.message());
            } else {
                logger.debug("Submitted {} for {}", commandType, player.getUsername());
            }
        });
    }

    private Map<String, Object> playerPayload(
        Player player,
        DataAuthority.CommandType commandType,
        boolean online,
        long now
    ) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("playerId", player.getUniqueId().toString());
        payload.put("username", player.getUsername());
        payload.put("timestamp", now);
        payload.put("online", online);
        payload.put("protocolVersion", player.getProtocolVersion().getProtocol());
        payload.put("currentProxy", proxyId);

        UUID sessionId;
        if (commandType == DataAuthority.CommandType.START_SESSION) {
            sessionId = UUID.randomUUID();
            activeSessions.put(player.getUniqueId(), sessionId);
        } else if (commandType == DataAuthority.CommandType.END_SESSION) {
            sessionId = activeSessions.remove(player.getUniqueId());
        } else {
            sessionId = activeSessions.get(player.getUniqueId());
        }
        if (sessionId != null) {
            payload.put("sessionId", sessionId.toString());
        }

        if (player.getRemoteAddress() != null) {
            payload.put("lastIp", player.getRemoteAddress().getAddress().getHostAddress());
        }

        player.getCurrentServer().ifPresent(server ->
            payload.put("currentServer", server.getServerInfo().getName()));

        if (commandType == DataAuthority.CommandType.START_SESSION) {
            payload.put("lastProxySession", now);
        }

        if (commandType == DataAuthority.CommandType.RENEW_SESSION) {
            payload.put("lastServerSwitch", now);
        }

        if (commandType == DataAuthority.CommandType.END_SESSION) {
            payload.put("playtimeStartField", "lastProxySession");
            payload.put("clearCurrentServer", true);
        }

        return payload;
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
