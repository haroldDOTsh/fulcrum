package sh.harold.fulcrum.velocity.fundamentals.commands;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.api.messagebus.messages.PlayerLocateRequest;
import sh.harold.fulcrum.api.messagebus.messages.PlayerLocateResponse;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.api.rank.Rank;
import sh.harold.fulcrum.velocity.api.rank.VelocityRankUtils;
import sh.harold.fulcrum.velocity.fundamentals.routing.PlayerRoutingFeature;
import sh.harold.fulcrum.velocity.session.VelocityPlayerSessionService;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Velocity command that locates a player anywhere on the network.
 */
final class LocatePlayerCommand implements SimpleCommand {
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(3);

    private final ProxyServer proxy;
    private final MessageBus messageBus;
    private final PlayerRoutingFeature routingFeature;
    private final FulcrumVelocityPlugin plugin;
    private final Logger logger;
    private final DataAPI dataAPI;
    private final VelocityPlayerSessionService sessionService;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    LocatePlayerCommand(ProxyServer proxy,
                        MessageBus messageBus,
                        PlayerRoutingFeature routingFeature,
                        FulcrumVelocityPlugin plugin,
                        Logger logger,
                        DataAPI dataAPI,
                        VelocityPlayerSessionService sessionService) {
        this.proxy = proxy;
        this.messageBus = messageBus;
        this.routingFeature = routingFeature;
        this.plugin = plugin;
        this.logger = logger;
        this.dataAPI = dataAPI;
        this.sessionService = sessionService;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] arguments = Arrays.copyOf(invocation.arguments(), invocation.arguments().length);

        VelocityRankUtils.hasRankOrHigher(source, Rank.HELPER, sessionService, dataAPI, logger)
                .whenComplete((allowed, throwable) -> {
                    if (throwable != null) {
                        logger.warn("Failed to verify rank for /locateplayer", throwable);
                        proxy.getScheduler().buildTask(plugin, () ->
                                        source.sendMessage(Component.text(
                                                "Unable to verify your permissions right now.",
                                                NamedTextColor.RED)))
                                .schedule();
                        return;
                    }

                    if (!Boolean.TRUE.equals(allowed)) {
                        proxy.getScheduler().buildTask(plugin, () ->
                                        source.sendMessage(Component.text(
                                                "You must be Helper rank or higher to use /locateplayer.",
                                                NamedTextColor.RED)))
                                .schedule();
                        return;
                    }

                    proxy.getScheduler().buildTask(plugin, () ->
                            executeAuthorized(source, arguments)).schedule();
                });
    }

    private void executeAuthorized(CommandSource source, String[] arguments) {
        if (arguments.length < 1) {
            source.sendMessage(Component.text("Usage: /locateplayer <player>", NamedTextColor.RED));
            return;
        }

        String query = arguments[0];
        UUID queryId = parseUuid(query);
        String queryName = queryId == null ? query : null;

        Optional<Player> local = queryId != null ? proxy.getPlayer(queryId) : proxy.getPlayer(query);
        if (local.isPresent()) {
            sendLocalLocation(source, local.get());
            return;
        }

        PlayerLocateRequest request = new PlayerLocateRequest();
        UUID requestId = UUID.randomUUID();
        request.setRequestId(requestId);
        if (queryId != null) {
            request.setPlayerId(queryId);
        }
        if (queryName != null) {
            request.setPlayerName(queryName);
        }

        try {
            request.validate();
        } catch (IllegalStateException exception) {
            source.sendMessage(Component.text("Invalid locate request: " + exception.getMessage(), NamedTextColor.RED));
            return;
        }

        source.sendMessage(Component.text("Searching for player " + (queryName != null ? queryName : queryId) + "...", NamedTextColor.GRAY));

        CompletableFuture<PlayerLocateResponse> future = new CompletableFuture<>();
        MessageHandler handler = envelope -> handleLocateResponse(envelope, requestId, future);
        messageBus.subscribe(ChannelConstants.REGISTRY_PLAYER_LOCATE_RESPONSE, handler);

        ScheduledTask timeoutTask = proxy.getScheduler().buildTask(plugin, () -> future.complete(null))
                .delay(RESPONSE_TIMEOUT)
                .schedule();

        future.whenComplete((response, throwable) -> {
            timeoutTask.cancel();
            messageBus.unsubscribe(ChannelConstants.REGISTRY_PLAYER_LOCATE_RESPONSE, handler);
            proxy.getScheduler().buildTask(plugin, () -> {
                if (throwable != null) {
                    logger.warn("Locate player request failed", throwable);
                    source.sendMessage(Component.text("Failed to locate player due to an internal error.", NamedTextColor.RED));
                    return;
                }
                if (response == null || !response.isFound()) {
                    String label = queryName != null ? queryName : (queryId != null ? queryId.toString() : "unknown");
                    source.sendMessage(Component.text("Player " + label + " is not online.", NamedTextColor.RED));
                    return;
                }
                sendRemoteLocation(source, response);
            }).schedule();
        });

        messageBus.broadcast(ChannelConstants.REGISTRY_PLAYER_LOCATE_REQUEST, request);
    }

    private void sendLocalLocation(CommandSource source, Player player) {
        PlayerRoutingFeature.PlayerLocationSnapshot snapshot = routingFeature.getPlayerLocation(player.getUniqueId())
                .orElse(null);

        String serverId = null;
        String slotSuffix = null;
        String familyId = null;
        if (snapshot != null) {
            serverId = snapshot.getServerId();
            slotSuffix = snapshot.getSlotSuffix();
            familyId = snapshot.getFamilyId();
        }

        if (serverId == null) {
            serverId = player.getCurrentServer()
                    .map(current -> current.getServerInfo().getName())
                    .orElse("unknown");
        }

        if (familyId == null && snapshot != null) {
            Map<String, String> metadata = snapshot.getMetadata();
            familyId = metadata.getOrDefault("family", metadata.getOrDefault("familyId", null));
        }

        String displayServer = formatServerId(serverId, slotSuffix);
        String displayFamily = familyId != null && !familyId.isBlank() ? familyId : "unknown";
        source.sendMessage(Component.text(
                "Player " + player.getUsername() + " spotted on " + displayServer + " (" + displayFamily + ")",
                NamedTextColor.GREEN));
    }

    private void sendRemoteLocation(CommandSource source, PlayerLocateResponse response) {
        String resolvedName = response.getPlayerName() != null ? response.getPlayerName()
                : (response.getPlayerId() != null ? response.getPlayerId().toString() : "unknown");
        String serverId = Optional.ofNullable(response.getServerId()).orElse("unknown");
        String slotSuffix = response.getSlotSuffix();
        String familyId = response.getFamilyId();
        String displayServer = formatServerId(serverId, slotSuffix);
        String displayFamily = familyId != null && !familyId.isBlank() ? familyId : "unknown";

        Component message = Component.text(
                "Player " + resolvedName + " spotted on " + displayServer + " (" + displayFamily + ")",
                NamedTextColor.GREEN);
        source.sendMessage(message);
    }

    private void handleLocateResponse(MessageEnvelope envelope,
                                      UUID requestId,
                                      CompletableFuture<PlayerLocateResponse> future) {
        if (future.isDone()) {
            return;
        }
        try {
            PlayerLocateResponse response = convert(envelope.payload(), PlayerLocateResponse.class);
            if (response == null || !requestId.equals(response.getRequestId())) {
                return;
            }
            if (!response.isFound()) {
                future.complete(response);
                return;
            }
            future.complete(response);
        } catch (Exception exception) {
            future.completeExceptionally(exception);
        }
    }

    private <T> T convert(Object payload, Class<T> type) {
        if (type.isInstance(payload)) {
            return type.cast(payload);
        }
        return objectMapper.convertValue(payload, type);
    }

    private UUID parseUuid(String input) {
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String formatServerId(String serverId, String slotSuffix) {
        if (slotSuffix == null || slotSuffix.isBlank()) {
            return serverId;
        }
        return serverId + slotSuffix;
    }
}
