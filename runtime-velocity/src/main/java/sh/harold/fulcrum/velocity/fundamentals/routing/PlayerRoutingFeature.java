package sh.harold.fulcrum.velocity.fundamentals.routing;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.Scheduler;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.api.messagebus.messages.PlayerRouteAck;
import sh.harold.fulcrum.api.messagebus.messages.PlayerRouteCommand;
import sh.harold.fulcrum.api.messagebus.messages.PlayerSlotRequest;
import sh.harold.fulcrum.api.messagebus.messages.PlayerLocateRequest;
import sh.harold.fulcrum.api.messagebus.messages.PlayerLocateResponse;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.fundamentals.messagebus.VelocityMessageBusFeature;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;
import net.kyori.adventure.text.format.TextColor;

/**
 * Handles matchmaking requests, route commands, and acknowledgements on the Velocity proxy.
 */
public class PlayerRoutingFeature implements VelocityFeature {
    private static final ChannelIdentifier ROUTE_CHANNEL = MinecraftChannelIdentifier.from("fulcrum:route");

    private final ObjectMapper objectMapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ProxyServer proxy;
    private Logger logger;
    private MessageBus messageBus;
    private VelocityMessageBusFeature messageBusFeature;
    private FulcrumVelocityPlugin plugin;
    private CommandManager commandManager;
    private Scheduler scheduler;

    private String subscribedProxyId;
    private String subscribedChannel;
    private MessageHandler routeHandler;
    private MessageHandler locateHandler;
    private final ConcurrentMap<UUID, PlayerLocationRecord> playerLocations = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "PlayerRouting";
    }

    @Override
    public int getPriority() {
        return 65; // After lifecycle has server registry awareness but before commands (100)
    }

    @Override
    public String[] getDependencies() {
        return new String[] { "VelocityMessageBus", "VelocityServerLifecycle" };
    }

    @Override
    public void initialize(ServiceLocator locator, Logger logger) {
        this.logger = logger;
        this.proxy = locator.getRequiredService(ProxyServer.class);
        this.messageBus = locator.getRequiredService(MessageBus.class);
        this.messageBusFeature = locator.getRequiredService(VelocityMessageBusFeature.class);
        this.plugin = locator.getRequiredService(FulcrumVelocityPlugin.class);
        this.commandManager = proxy.getCommandManager();
        this.scheduler = proxy.getScheduler();

        proxy.getChannelRegistrar().register(ROUTE_CHANNEL);
        proxy.getEventManager().register(plugin, this);

        routeHandler = this::handleRouteEnvelope;
        locateHandler = this::handleLocateEnvelope;
        messageBus.subscribe(ChannelConstants.REGISTRY_PLAYER_LOCATE_REQUEST, locateHandler);
        subscribeToProxyChannel(messageBusFeature.getCurrentProxyId());

        registerRouteCommand();

        logger.info("PlayerRoutingFeature initialized");
    }

    @Override
    public void shutdown() {
        if (subscribedChannel != null && routeHandler != null) {
            messageBus.unsubscribe(subscribedChannel, routeHandler);
        }
        if (locateHandler != null) {
            messageBus.unsubscribe(ChannelConstants.REGISTRY_PLAYER_LOCATE_REQUEST, locateHandler);
        }
        proxy.getChannelRegistrar().unregister(ROUTE_CHANNEL);
        proxy.getEventManager().unregisterListener(plugin, this);
        logger.info("PlayerRoutingFeature shut down");
    }

    public void onProxyIdUpdated(String newProxyId) {
        subscribeToProxyChannel(newProxyId);
    }

    private void subscribeToProxyChannel(String proxyId) {
        if (proxyId == null || proxyId.isBlank()) {
            logger.warn("Cannot subscribe to player route channel; proxyId is blank");
            return;
        }

        String channel = ChannelConstants.getPlayerRouteChannel(proxyId);
        if (Objects.equals(channel, subscribedChannel)) {
            return;
        }

        if (subscribedChannel != null && routeHandler != null) {
            messageBus.unsubscribe(subscribedChannel, routeHandler);
            logger.info("Unsubscribed from player route channel {}", subscribedChannel);
        }

        messageBus.subscribe(channel, routeHandler);
        subscribedChannel = channel;
        subscribedProxyId = proxyId;
        logger.info("Subscribed to player route channel {}", channel);
    }

    private void handleLocateEnvelope(MessageEnvelope envelope) {
        try {
            PlayerLocateRequest request = convert(envelope.getPayload(), PlayerLocateRequest.class);
            if (request == null || request.getRequestId() == null) {
                return;
            }
            logger.info("Proxy received locate request {} for player {}", request.getRequestId(), request.getPlayerName());

            java.util.Optional<Player> playerOpt = java.util.Optional.empty();
            if (request.getPlayerId() != null) {
                playerOpt = proxy.getPlayer(request.getPlayerId());
            }
            if (playerOpt.isEmpty() && request.getPlayerName() != null && !request.getPlayerName().isBlank()) {
                playerOpt = proxy.getPlayer(request.getPlayerName());
            }

            if (playerOpt.isEmpty()) {
                logger.debug("Locate request {} not matched on proxy {}", request.getRequestId(), currentProxyId());
                return;
            }

            Player player = playerOpt.get();
            logger.info("Located player {} on proxy {}", player.getUsername(), currentProxyId());
            PlayerLocateResponse response = new PlayerLocateResponse();
            response.setRequestId(request.getRequestId());
            response.setProxyId(currentProxyId());
            response.setPlayerId(player.getUniqueId());
            response.setPlayerName(player.getUsername());
            PlayerLocationSnapshot snapshot = getPlayerLocation(player.getUniqueId()).orElse(null);
            if (snapshot != null) {
                response.setServerId(snapshot.getServerId());
                response.setSlotId(snapshot.getSlotId());
                response.setSlotSuffix(snapshot.getSlotSuffix());
                response.setFamilyId(snapshot.getFamilyId());
            } else {
                player.getCurrentServer().ifPresent(server -> response.setServerId(server.getServerInfo().getName()));
            }
            response.setFound(response.getServerId() != null && !response.getServerId().isBlank());
            messageBus.broadcast(ChannelConstants.REGISTRY_PLAYER_LOCATE_RESPONSE, response);
        } catch (Exception exception) {
            logger.warn("Failed to handle player locate request", exception);
        }
    }

    private void handleRouteEnvelope(MessageEnvelope envelope) {
        scheduler.buildTask(plugin, () -> {
            try {
                PlayerRouteCommand command = convert(envelope.getPayload(), PlayerRouteCommand.class);
                logger.info("Proxy received route command: requestId={} player={} targetServer={} slot={} world={}",
                    command.getRequestId(), command.getPlayerName(), command.getServerId(),
                    command.getSlotId(), command.getTargetWorld());
                command.validate();
                if (command.getAction() == PlayerRouteCommand.Action.ROUTE) {
                    handleRouteCommand(command);
                } else if (command.getAction() == PlayerRouteCommand.Action.DISCONNECT) {
                    handleDisconnectCommand(command);
                }
            } catch (Exception exception) {
                logger.error("Failed to handle player route command", exception);
            }
        }).schedule();
    }

    private void handleRouteCommand(PlayerRouteCommand command) {
        Optional<Player> playerOpt = proxy.getPlayer(command.getPlayerId());
        if (playerOpt.isEmpty()) {
            sendFailureAck(command, "player-offline");
            return;
        }
        Player player = playerOpt.get();

        Optional<RegisteredServer> targetServer = proxy.getServer(command.getServerId());
        if (targetServer.isEmpty()) {
            sendFailureAck(command, "backend-not-found");
            return;
        }

        player.sendMessage(Component.text("Routing you to " + command.getSlotId() + "...", NamedTextColor.GRAY));

        if (player.getCurrentServer()
            .map(current -> current.getServerInfo().getName().equalsIgnoreCase(command.getServerId()))
            .orElse(false)) {
            logger.info("Player {} already connected to {}; delivering route payload directly",
                player.getUsername(), command.getServerId());
            scheduler.buildTask(plugin, () -> {
                logger.info("Sending route payload to backend for {} (slotId={}, world={})",
                    player.getUsername(), command.getSlotId(), command.getTargetWorld());
                sendRoutePluginMessage(player, command);
            }).delay(Duration.ofMillis(50))
                .schedule();
            recordPlayerLocation(command, player);
            sendSuccessAck(command);
            return;
        }

        player.createConnectionRequest(targetServer.get()).connect().whenComplete((result, throwable) -> {
            if (throwable != null) {
                logger.warn("Connection attempt for {} failed: {}", player.getUsername(), throwable.getMessage());
                sendFailureAck(command, "connection-failed");
                return;
            }

            if (!result.isSuccessful()) {
                logger.warn("Connection attempt for {} to {} was unsuccessful",
                    player.getUsername(), command.getServerId());
                sendFailureAck(command, "connection-failed");
                return;
            }

            scheduler.buildTask(plugin, () -> {
                logger.info("Sending route payload to backend for {} (slotId={}, world={})",
                    player.getUsername(), command.getSlotId(), command.getTargetWorld());
                sendRoutePluginMessage(player, command);
            }).delay(Duration.ofMillis(50))
                .schedule();
            recordPlayerLocation(command, player);
            sendSuccessAck(command);
        });
    }

    private void handleDisconnectCommand(PlayerRouteCommand command) {
        proxy.getPlayer(command.getPlayerId()).ifPresent(player -> {
            String reason = Optional.ofNullable(command.getMetadata())
                .map(meta -> meta.getOrDefault("reason", "Disconnected by registry"))
                .orElse("Disconnected by registry");
            player.disconnect(Component.text(reason, TextColor.color(0xFF5555)));
        });
        forgetPlayerLocation(command.getPlayerId());
    }

    private void sendRoutePluginMessage(Player player, PlayerRouteCommand command) {
        try {
            byte[] payload = objectMapper.writeValueAsString(command).getBytes(StandardCharsets.UTF_8);
            logger.debug("Plugin message size={} bytes for {}", payload.length, player.getUsername());
            player.getCurrentServer().ifPresentOrElse(server -> {
                server.sendPluginMessage(ROUTE_CHANNEL, payload);
                logger.trace("Delivered route payload via server connection {}", server.getServerInfo().getName());
            }, () -> logger.warn("Unable to deliver route payload for {}; player has no active server connection", player.getUsername()));
        } catch (Exception exception) {
            logger.warn("Failed to send route payload to backend for {}: {}",
                player.getUsername(), exception.getMessage());
        }
    }

    private void sendSuccessAck(PlayerRouteCommand command) {
        PlayerRouteAck ack = new PlayerRouteAck();
        ack.setRequestId(command.getRequestId());
        ack.setPlayerId(command.getPlayerId());
        ack.setProxyId(currentProxyId());
        ack.setServerId(command.getServerId());
        ack.setSlotId(command.getSlotId());
        ack.setStatus(PlayerRouteAck.Status.SUCCESS);
        messageBus.broadcast(ChannelConstants.PLAYER_ROUTE_ACK, ack);
    }

    private void sendFailureAck(PlayerRouteCommand command, String reason) {
        PlayerRouteAck ack = new PlayerRouteAck();
        ack.setRequestId(command.getRequestId());
        ack.setPlayerId(command.getPlayerId());
        ack.setProxyId(currentProxyId());
        ack.setServerId(command.getServerId());
        ack.setSlotId(command.getSlotId());
        ack.setStatus(PlayerRouteAck.Status.FAILED);
        ack.setReason(reason);
        messageBus.broadcast(ChannelConstants.PLAYER_ROUTE_ACK, ack);
    }

    private String currentProxyId() {
        return Optional.ofNullable(messageBusFeature.getCurrentProxyId()).orElse(subscribedProxyId != null ? subscribedProxyId : "temp-proxy");
    }

    private void registerRouteCommand() {
        CommandMeta meta = commandManager.metaBuilder("route")
            .plugin(plugin)
            .build();

        commandManager.register(meta, new SimpleCommand() {
            @Override
            public void execute(Invocation invocation) {
                if (invocation.arguments().length < 2) {
                    invocation.source().sendMessage(Component.text("Usage: /route <player> <family>", NamedTextColor.RED));
                    return;
                }

                String targetName = invocation.arguments()[0];
                String familyId = invocation.arguments()[1];

                Optional<Player> playerOpt = proxy.getPlayer(targetName);
                if (playerOpt.isEmpty()) {
                    invocation.source().sendMessage(Component.text("Player not found: " + targetName, NamedTextColor.RED));
                    return;
                }

                sendSlotRequest(playerOpt.get(), familyId, Map.of("source", "command"))
                    .thenAccept(requestId -> invocation.source().sendMessage(Component.text(
                        "Requested slot for " + targetName + " (family=" + familyId + ")", NamedTextColor.GREEN)));
            }
        });
    }

    public CompletableFuture<UUID> sendSlotRequest(Player player, String familyId, Map<String, String> metadata) {
        PlayerSlotRequest request = new PlayerSlotRequest();
        request.setPlayerId(player.getUniqueId());
        request.setPlayerName(player.getUsername());
        request.setProxyId(currentProxyId());
        request.setFamilyId(familyId);
        if (metadata != null && !metadata.isEmpty()) {
            request.setMetadata(metadata);
        }

        messageBus.broadcast(ChannelConstants.REGISTRY_PLAYER_REQUEST, request);
        return CompletableFuture.completedFuture(request.getRequestId());
    }

    private <T> T convert(Object payload, Class<T> type) {
        if (type.isInstance(payload)) {
            return type.cast(payload);
        }
        return objectMapper.convertValue(payload, type);
    }

    private void recordPlayerLocation(PlayerRouteCommand command, Player player) {
        Map<String, String> metadata = command.getMetadata();
        String familyId = metadata != null ? metadata.getOrDefault("family", metadata.get("familyId")) : null;
        PlayerLocationRecord record = new PlayerLocationRecord(
            command.getServerId(),
            command.getSlotId(),
            command.getSlotSuffix(),
            familyId,
            metadata
        );
        playerLocations.put(command.getPlayerId(), record);
        logger.debug("Updated location for {} -> {}{}", player.getUsername(), command.getServerId(),
            command.getSlotSuffix() != null ? command.getSlotSuffix() : "");
    }

    private void forgetPlayerLocation(UUID playerId) {
        if (playerId == null) {
            return;
        }
        playerLocations.remove(playerId);
    }

    public Optional<PlayerLocationSnapshot> getPlayerLocation(UUID playerId) {
        PlayerLocationRecord record = playerLocations.get(playerId);
        if (record == null) {
            return Optional.empty();
        }
        return Optional.of(record.snapshot());
    }

    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        forgetPlayerLocation(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        player.getCurrentServer().ifPresent(server -> playerLocations.compute(player.getUniqueId(), (id, existing) -> {
            if (existing == null) {
                return new PlayerLocationRecord(server.getServerInfo().getName(), null, null, null, Map.of());
            }
            return existing.withServer(server.getServerInfo().getName());
        }));
    }

    private record PlayerLocationRecord(
        String serverId,
        String slotId,
        String slotSuffix,
        String familyId,
        Map<String, String> metadataSnapshot,
        long updatedAt
    ) {
        private PlayerLocationRecord(String serverId,
                                     String slotId,
                                     String slotSuffix,
                                     String familyId,
                                     Map<String, String> metadata) {
            this(serverId, slotId, slotSuffix, familyId, metadata, System.currentTimeMillis());
        }

        private PlayerLocationRecord {
            metadataSnapshot = metadataSnapshot != null ? Map.copyOf(metadataSnapshot) : Map.of();
        }

        private PlayerLocationRecord withServer(String newServerId) {
            return new PlayerLocationRecord(newServerId, slotId, slotSuffix, familyId, metadataSnapshot, System.currentTimeMillis());
        }

        private PlayerLocationSnapshot snapshot() {
            return new PlayerLocationSnapshot(serverId, slotId, slotSuffix, familyId, metadataSnapshot, updatedAt);
        }
    }

    public record PlayerLocationSnapshot(
        String serverId,
        String slotId,
        String slotSuffix,
        String familyId,
        Map<String, String> metadata,
        long updatedAt
    ) {
        public PlayerLocationSnapshot {
            metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        }

        public String getServerId() {
            return serverId;
        }

        public String getSlotId() {
            return slotId;
        }

        public String getSlotSuffix() {
            return slotSuffix;
        }

        public String getFamilyId() {
            return familyId;
        }

        public Map<String, String> getMetadata() {
            return metadata;
        }

        public long getUpdatedAt() {
            return updatedAt;
        }
    }
}
