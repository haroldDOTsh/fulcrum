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
import sh.harold.fulcrum.api.lifecycle.ServerIdentifier;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.messages.PlayerRouteAck;
import sh.harold.fulcrum.api.messagebus.messages.PlayerRouteCommand;
import sh.harold.fulcrum.fundamentals.session.PlayerReservationService;
import sh.harold.fulcrum.fundamentals.session.PlayerSessionService;
import sh.harold.fulcrum.fundamentals.slot.presence.SlotPresenceService;
import sh.harold.fulcrum.minigame.GameManager;
import sh.harold.fulcrum.minigame.party.PartyReservationConsumer;
import sh.harold.fulcrum.minigame.routing.PlayerRouteRegistry;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receives routing metadata from the proxy and teleports players to the designated spawn.
 */
public final class PlayerRoutingListener implements Listener, PluginMessageListener {
    private static final String CHANNEL = "fulcrum:route";
    private static final Duration PROCESSED_REQUEST_TTL = Duration.ofSeconds(30);
    private static final Duration HANDOFF_TTL_FALLBACK = Duration.ofSeconds(15);

    private final Plugin plugin;
    private final PlayerRouteRegistry routeRegistry;
    private final GameManager gameManager;
    private final PlayerReservationService reservationService;
    private final PartyReservationConsumer partyReservationConsumer;
    private final MessageBus messageBus;
    private final PlayerSessionService sessionService;
    private final ObjectMapper objectMapper;
    private final Map<UUID, PendingTeleport> pendingTeleports = new ConcurrentHashMap<>();
    private final Map<UUID, Long> processedRequests = new ConcurrentHashMap<>();
    private final String localServerId;
    private final SlotPresenceService slotPresence;

    public PlayerRoutingListener(Plugin plugin,
                                 PlayerRouteRegistry routeRegistry,
                                 GameManager gameManager,
                                 PlayerReservationService reservationService,
                                 PartyReservationConsumer partyReservationConsumer,
                                 MessageBus messageBus,
                                 PlayerSessionService sessionService,
                                 ServerIdentifier serverIdentifier,
                                 SlotPresenceService slotPresence) {
        this.plugin = plugin;
        this.routeRegistry = routeRegistry;
        this.gameManager = gameManager;
        this.reservationService = reservationService;
        this.partyReservationConsumer = partyReservationConsumer;
        this.messageBus = messageBus;
        this.sessionService = sessionService;
        this.slotPresence = slotPresence;
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.localServerId = serverIdentifier != null && serverIdentifier.getServerId() != null
                ? serverIdentifier.getServerId()
                : plugin.getServer().getName();

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

        UUID requestId = command.getRequestId();
        if (requestId != null && isDuplicateRequest(requestId)) {
            plugin.getLogger().fine("Ignoring duplicate route command " + requestId);
            return;
        }

        UUID playerId = command.getPlayerId();
        Player player = Bukkit.getPlayer(playerId);
        boolean skipTeleport = shouldSkipTeleport(command);

        Location target = null;
        if (skipTeleport && player != null) {
            Location current = player.getLocation();
            if (current != null) {
                target = current.clone();
            }
        }
        if (target == null) {
            target = resolveLocation(command);
        }
        if (target == null) {
            plugin.getLogger().warning("Route command missing valid target location for player " + command.getPlayerName());
            return;
        }

        Map<String, String> commandMetadata = command.getMetadata() != null ? new HashMap<>(command.getMetadata()) : new HashMap<>();
        String reservationToken = commandMetadata.get("reservationToken");
        boolean hasReservationToken = reservationToken != null && !reservationToken.isBlank();
        String partyReservationId = commandMetadata.get("partyReservationId");
        String partyTokenId = commandMetadata.get("partyTokenId");
        boolean localRoute = isLocalRoute(command, player);

        if (partyReservationId != null && !partyReservationId.isBlank()
                && partyTokenId != null && !partyTokenId.isBlank()) {
            if (partyReservationConsumer == null
                    || !partyReservationConsumer.consume(partyReservationId, partyTokenId, playerId,
                    command.getServerId(), command.getSlotId())) {
                plugin.getLogger().warning("Rejected party route for " + command.getPlayerName() + " due to reservation validation failure");
                sendReservationFailure(command, "party-reservation-invalid");
                return;
            }
        } else if (reservationService != null && hasReservationToken) {
            boolean consumed = reservationService.consumeReservation(reservationToken, playerId);
            if (!consumed) {
                if (localRoute) {
                    plugin.getLogger().fine(() -> "Bypassing reservation validation for local route to slot "
                            + command.getSlotId() + " (token missing or expired).");
                } else {
                    plugin.getLogger().warning("Rejected route for " + command.getPlayerName() + " due to missing or invalid reservation token");
                    sendReservationFailure(command, "invalid-reservation");
                    return;
                }
            }
        } else if (reservationService != null && !hasReservationToken) {
            plugin.getLogger().fine(() -> "Route command for " + command.getPlayerName()
                    + " did not include a reservation token; proceeding without validation.");
        } else {
            plugin.getLogger().warning("PlayerReservationService unavailable; accepting route without reservation validation");
        }

        Map<String, String> metadata = new HashMap<>(commandMetadata);
        metadata.remove("reservationToken");
        metadata.remove("partyReservationId");
        metadata.remove("partyTokenId");
        if (command.getProxyId() != null && !command.getProxyId().isBlank()) {
            metadata.putIfAbsent("proxyId", command.getProxyId());
        }
        if (sessionService != null) {
            sessionService.recordHandoff(
                    playerId,
                    command.getServerId(),
                    command.getSlotId(),
                    reservationToken,
                    metadata,
                    reservationService != null ? reservationService.getReservationTtl() : HANDOFF_TTL_FALLBACK
            );
            sessionService.updateMinigameContext(playerId, metadata, command.getSlotId());
        }
        command.setMetadata(metadata);

        if (gameManager != null && player != null) {
            gameManager.handleLocalRoute(player);
        }

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

        if (skipTeleport) {
            plugin.getLogger().fine(() -> "Skipping teleport for " + command.getPlayerName()
                    + " (routeType=" + metadata.getOrDefault("routeType", "unknown") + ")");
            sendSuccessAck(command);
        } else {
            pendingTeleports.put(playerId, new PendingTeleport(target, command, System.currentTimeMillis()));
            attemptTeleport(playerId);
        }

        if (gameManager != null) {
            Player routed = Bukkit.getPlayer(playerId);
            if (routed != null) {
                gameManager.handleRoutedPlayer(routed, assignment);
            }
        }
    }

    private boolean shouldSkipTeleport(PlayerRouteCommand command) {
        if (command == null) {
            return false;
        }
        Map<String, String> metadata = command.getMetadata();
        if (metadata == null) {
            return false;
        }
        String flag = metadata.get("skipTeleport");
        if (flag == null) {
            return false;
        }
        return flag.equalsIgnoreCase("true") || flag.equals("1");
    }

    private void sendReservationFailure(PlayerRouteCommand command, String reason) {
        sendFailureAck(command, reason);
    }

    private void sendFailureAck(PlayerRouteCommand command, String reason) {
        if (messageBus != null) {
            PlayerRouteAck ack = new PlayerRouteAck();
            ack.setRequestId(command.getRequestId());
            ack.setPlayerId(command.getPlayerId());
            ack.setProxyId(command.getProxyId());
            ack.setServerId(command.getServerId());
            ack.setSlotId(command.getSlotId());
            ack.setStatus(PlayerRouteAck.Status.FAILED);
            ack.setReason(reason);
            messageBus.broadcast(ChannelConstants.PLAYER_ROUTE_ACK, ack);
        }
        if (sessionService != null && command.getPlayerId() != null) {
            sessionService.clearHandoff(command.getPlayerId());
            sessionService.clearMinigameContext(command.getPlayerId());
        }
        if (slotPresence != null && command.getPlayerId() != null) {
            slotPresence.unbindPlayer(command.getPlayerId());
        }
    }

    private boolean isLocalRoute(PlayerRouteCommand command, Player player) {
        if (player == null) {
            return false;
        }
        if (localServerId == null || localServerId.isBlank()) {
            return false;
        }
        String targetServer = command.getServerId();
        if (targetServer == null || targetServer.isBlank()) {
            return false;
        }
        return targetServer.equalsIgnoreCase(localServerId);
    }

    private void sendSuccessAck(PlayerRouteCommand command) {
        if (messageBus != null) {
            PlayerRouteAck ack = new PlayerRouteAck();
            ack.setRequestId(command.getRequestId());
            ack.setPlayerId(command.getPlayerId());
            ack.setProxyId(command.getProxyId());
            ack.setServerId(command.getServerId());
            ack.setSlotId(command.getSlotId());
            ack.setStatus(PlayerRouteAck.Status.SUCCESS);
            messageBus.broadcast(ChannelConstants.PLAYER_ROUTE_ACK, ack);
        }
        if (sessionService != null) {
            Map<String, String> metadata = command.getMetadata() != null ? command.getMetadata() : Map.of();
            Map<String, Object> segmentMetadata = new HashMap<>(metadata);
            segmentMetadata.put("slotId", command.getSlotId());
            segmentMetadata.put("serverId", command.getServerId());
            segmentMetadata.putIfAbsent("phase", "pre_lobby");
            segmentMetadata.putIfAbsent("queue", Boolean.TRUE);
            String context = metadata.getOrDefault("family", command.getSlotId());
            sessionService.startSegment(command.getPlayerId(), "MINIGAME", context, segmentMetadata, command.getServerId());
            sessionService.clearHandoff(command.getPlayerId());
            sessionService.updateMinigameContext(command.getPlayerId(),
                    metadata,
                    command.getSlotId());
        }
        if (slotPresence != null && command.getPlayerId() != null && command.getSlotId() != null) {
            Player player = Bukkit.getPlayer(command.getPlayerId());
            String playerName = player != null ? player.getName() : command.getPlayerName();
            slotPresence.bindPlayer(command.getPlayerId(), playerName, command.getSlotId());
            if (command.getTargetWorld() != null && !command.getTargetWorld().isBlank()) {
                slotPresence.bindWorld(command.getTargetWorld(), command.getSlotId());
            }
        }
    }

    private void handleDisconnect(PlayerRouteCommand command) {
        UUID playerId = command.getPlayerId();
        pendingTeleports.remove(playerId);
        routeRegistry.remove(playerId);

        if (sessionService != null) {
            sessionService.endActiveSegment(playerId);
            sessionService.clearHandoff(playerId);
            sessionService.clearMinigameContext(playerId);
        }
        if (slotPresence != null && playerId != null) {
            slotPresence.unbindPlayer(playerId);
        }

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
                sendFailureAck(pending.command(), "invalid-target");
                return;
            }

            long ageMillis = System.currentTimeMillis() - pending.enqueuedAt();
            plugin.getLogger().fine(() -> "Teleporting " + player.getName()
                    + " to " + describeLocation(location) + " (async); queuedForMs=" + ageMillis);

            long startTime = System.currentTimeMillis();
            player.teleportAsync(location).whenComplete((result, error) -> {
                long durationMs = System.currentTimeMillis() - startTime;
                if (error != null || Boolean.FALSE.equals(result)) {
                    plugin.getLogger().warning(() -> "Async teleport for " + player.getName()
                            + " failed after " + durationMs + "ms result=" + result
                            + (error != null ? " cause=" + describeError(error) : "")
                            + "; falling back to synchronous teleport");
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        plugin.getLogger().fine(() -> "Attempting synchronous teleport for " + player.getName());
                        boolean success = player.teleport(location);
                        if (success) {
                            plugin.getLogger().info(() -> "Synchronous teleport for "
                                    + player.getName() + " succeeded");
                            sendSuccessAck(pending.command());
                        } else {
                            plugin.getLogger().warning(() -> "Synchronous teleport for "
                                    + player.getName() + " failed");
                            sendFailureAck(pending.command(), "teleport-failed");
                        }
                    });
                } else {
                    plugin.getLogger().fine(() -> "Async teleport for "
                            + player.getName() + " succeeded in " + durationMs + "ms");
                    sendSuccessAck(pending.command());
                }
            });
        });
    }

    private boolean isDuplicateRequest(UUID requestId) {
        long now = System.currentTimeMillis();
        Long previous = processedRequests.putIfAbsent(requestId, now);
        if (previous == null) {
            cleanupProcessedRequests(now);
            return false;
        }
        if (now - previous <= PROCESSED_REQUEST_TTL.toMillis()) {
            return true;
        }
        processedRequests.put(requestId, now);
        cleanupProcessedRequests(now);
        return false;
    }

    private void cleanupProcessedRequests(long now) {
        if (processedRequests.isEmpty()) {
            return;
        }
        processedRequests.entrySet().removeIf(entry -> now - entry.getValue() > PROCESSED_REQUEST_TTL.toMillis());
    }

    private String describeError(Throwable error) {
        if (error == null) {
            return "";
        }
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }

    private String describeLocation(Location location) {
        if (location == null) {
            return "unknown";
        }
        World world = location.getWorld();
        String worldName = world != null ? world.getName() : "unknown";
        return worldName + " @ " + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
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
        if (gameManager != null) {
            gameManager.handlePlayerQuit(event.getPlayer());
        }
        routeRegistry.remove(playerId);
        if (slotPresence != null) {
            slotPresence.unbindPlayer(playerId);
        }
    }

    private record PendingTeleport(Location location, PlayerRouteCommand command, long enqueuedAt) {
    }
}
