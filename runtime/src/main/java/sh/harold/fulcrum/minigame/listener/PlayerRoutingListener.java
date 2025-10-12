package sh.harold.fulcrum.minigame.listener;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitScheduler;
import sh.harold.fulcrum.api.messagebus.messages.PlayerRouteCommand;
import sh.harold.fulcrum.minigame.GameManager;
import sh.harold.fulcrum.minigame.routing.PlayerRouteRegistry;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receives routing metadata from the proxy and teleports players to the designated spawn.
 */
public final class PlayerRoutingListener implements Listener, PluginMessageListener {
    private static final String CHANNEL = "fulcrum:route";

    private final Plugin plugin;
    private final PlayerRouteRegistry routeRegistry;
    private final GameManager gameManager;
    private final ObjectMapper objectMapper;
    private final Map<UUID, PendingTeleport> pendingTeleports = new ConcurrentHashMap<>();

    public PlayerRoutingListener(Plugin plugin,
                                 PlayerRouteRegistry routeRegistry,
                                 GameManager gameManager) {
        this.plugin = plugin;
        this.routeRegistry = routeRegistry;
        this.gameManager = gameManager;
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
    }

    public void shutdown() {
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
        pendingTeleports.clear();
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equalsIgnoreCase(channel)) {
            return;
        }
        if (message == null || message.length == 0) {
            return;
        }

        try {
            String payload = new String(message, StandardCharsets.UTF_8);
            PlayerRouteCommand command = objectMapper.readValue(payload, PlayerRouteCommand.class);
            if (command.getPlayerId() == null) {
                return;
            }

            switch (command.getAction()) {
                case DISCONNECT -> handleDisconnect(command);
                case ROUTE -> handleRouteCommand(command);
                default -> {
                }
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to process player route payload: " + exception.getMessage());
        }
    }

    public void handleRouteCommand(PlayerRouteCommand command) {
        plugin.getLogger().info(() -> "Received route command for " + command.getPlayerName()
                + " (slot=" + command.getSlotId() + ", world=" + command.getTargetWorld() + ")");
        UUID playerId = command.getPlayerId();
        Location target = resolveLocation(command);
        if (target == null) {
            plugin.getLogger().warning("Route command missing valid target location for player " + command.getPlayerName());
            return;
        }

        Map<String, String> metadata = command.getMetadata() != null ? command.getMetadata() : Map.of();
        PlayerRouteRegistry.RouteAssignment assignment = new PlayerRouteRegistry.RouteAssignment(
                playerId,
                command.getPlayerName(),
                command.getSlotId(),
                metadata.getOrDefault("family", ""),
                metadata.getOrDefault("variant", ""),
                command.getProxyId(),
                target.getWorld() != null ? target.getWorld().getName() : "",
                metadata
        );
        routeRegistry.register(assignment);

        pendingTeleports.put(playerId, new PendingTeleport(target));
        attemptTeleport(playerId);

        if (gameManager != null) {
            Player routed = Bukkit.getPlayer(playerId);
            if (routed != null) {
                gameManager.handleRoutedPlayer(routed, assignment);
            }
        }
    }

    private void handleDisconnect(PlayerRouteCommand command) {
        UUID playerId = command.getPlayerId();
        pendingTeleports.remove(playerId);
        routeRegistry.remove(playerId);

        Player target = Bukkit.getPlayer(playerId);
        if (target != null) {
            String reason = Optional.ofNullable(command.getMetadata())
                    .map(meta -> meta.getOrDefault("reason", "Disconnected"))
                    .orElse("Disconnected");
            target.kickPlayer(reason);
        }
    }

    private void attemptTeleport(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }

        PendingTeleport pending = pendingTeleports.remove(playerId);
        if (pending == null) {
            return;
        }

        BukkitScheduler scheduler = plugin.getServer().getScheduler();
        scheduler.runTask(plugin, () -> {
            Location location = pending.location();
            if (location.getWorld() == null) {
                plugin.getLogger().warning("Cannot teleport player " + player.getName() + " - world unavailable");
                return;
            }

            plugin.getLogger().info(() -> "Routing " + player.getName() + " to world="
                    + location.getWorld().getName() + " x=" + location.getX()
                    + " y=" + location.getY() + " z=" + location.getZ()
                    + " yaw=" + location.getYaw() + " pitch=" + location.getPitch());

            player.teleportAsync(location).whenComplete((result, error) -> {
                if (error != null || Boolean.FALSE.equals(result)) {
                    plugin.getLogger().warning("Async teleport failed for " + player.getName()
                            + "; falling back to synchronous teleport." + (error != null ? " Cause: " + error.getMessage() : ""));
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        boolean success = player.teleport(location);
                        if (success) {
                            plugin.getLogger().info("Synchronous teleport succeeded for " + player.getName());
                        } else {
                            plugin.getLogger().warning("Synchronous teleport failed for " + player.getName());
                        }
                    });
                } else {
                    plugin.getLogger().info("Async teleport succeeded for " + player.getName());
                }
            });
        });
    }

    private Location resolveLocation(PlayerRouteCommand command) {
        String worldName = command.getTargetWorld();
        World world = worldName != null && !worldName.isBlank() ? Bukkit.getWorld(worldName) : Bukkit.getWorlds().stream().findFirst().orElse(null);
        if (world == null) {
            return null;
        }
        Location location = new Location(world, command.getSpawnX(), command.getSpawnY(), command.getSpawnZ(), command.getSpawnYaw(), command.getSpawnPitch());
        return location;
    }

    @EventHandler
    public void handlePlayerJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (pendingTeleports.containsKey(playerId)) {
            attemptTeleport(playerId);
        }
        if (gameManager != null) {
            routeRegistry.get(playerId).ifPresent(assignment ->
                    gameManager.handleRoutedPlayer(event.getPlayer(), assignment));
        }
    }

    @EventHandler
    public void handlePlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        pendingTeleports.remove(playerId);
        routeRegistry.remove(playerId);
    }

    private record PendingTeleport(Location location) {
    }
}
