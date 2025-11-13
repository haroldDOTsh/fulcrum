package sh.harold.fulcrum.fundamentals.shutdown;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import sh.harold.fulcrum.api.lifecycle.ServerIdentifier;
import sh.harold.fulcrum.api.message.scoreboard.render.RenderingPipeline;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.api.messagebus.messages.PlayerSlotRequest;
import sh.harold.fulcrum.api.messagebus.messages.ShutdownIntentMessage;
import sh.harold.fulcrum.api.messagebus.messages.ShutdownIntentUpdateMessage;
import sh.harold.fulcrum.fundamentals.lifecycle.ServerLifecycleFeature;
import sh.harold.fulcrum.fundamentals.session.PlayerSessionService;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;
import sh.harold.fulcrum.minigame.routing.PlayerRouteRegistry;
import sh.harold.fulcrum.minigame.routing.PlayerRouteRegistry.RouteAssignment;
import sh.harold.fulcrum.session.PlayerSessionRecord;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.papermc.paper.command.brigadier.Commands.literal;

/**
 * Handles registry-issued shutdown intents for Paper runtimes.
 */
public final class ServerShutdownFeature implements PluginFeature, Listener {
    private static final Duration EVICT_BUFFER = Duration.ofSeconds(3);
    private static final int FINAL_REMINDER_SECOND = 10;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private JavaPlugin plugin;
    private DependencyContainer container;
    private MessageBus messageBus;
    private ServerIdentifier serverIdentifier;
    private PlayerRouteRegistry routeRegistry;
    private RenderingPipeline renderingPipeline;
    private ServerLifecycleFeature lifecycleFeature;
    private PlayerSessionService sessionService;
    private EvacuationContext currentContext;
    private MessageHandler shutdownHandler;

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        this.container = container;
        this.messageBus = container.getOptional(MessageBus.class)
                .orElseThrow(() -> new IllegalStateException("MessageBus unavailable for shutdown feature"));
        this.serverIdentifier = container.getOptional(ServerIdentifier.class)
                .orElseThrow(() -> new IllegalStateException("ServerIdentifier unavailable for shutdown feature"));
        this.routeRegistry = container.getOptional(PlayerRouteRegistry.class).orElse(null);
        this.renderingPipeline = container.getOptional(RenderingPipeline.class).orElse(null);
        this.lifecycleFeature = container.getOptional(ServerLifecycleFeature.class).orElse(null);
        this.sessionService = container.getOptional(PlayerSessionService.class).orElse(null);

        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        shutdownHandler = this::handleShutdownIntent;
        messageBus.subscribe(ChannelConstants.REGISTRY_SHUTDOWN_INTENT, shutdownHandler);

        CommandRegistrar.register(buildEvacuateCommand());
    }

    @Override
    public void shutdown() {
        if (messageBus != null && shutdownHandler != null) {
            messageBus.unsubscribe(ChannelConstants.REGISTRY_SHUTDOWN_INTENT, shutdownHandler);
        }
        cancelContext();
        routeRegistry = null;
        sessionService = null;
        container = null;
    }

    @EventHandler
    public void handlePlayerJoin(PlayerJoinEvent event) {
        if (currentContext != null) {
            sendCountdownPrompt(event.getPlayer(), currentContext.secondsRemaining);
        }
    }

    private LiteralCommandNode<CommandSourceStack> buildEvacuateCommand() {
        return literal("evacuate")
                .requires(stack -> stack.getSender() instanceof Player)
                .executes(ctx -> {
                    Player player = (Player) ctx.getSource().getSender();
                    if (currentContext == null) {
                        player.sendMessage(Component.text("No evacuation in progress.", NamedTextColor.GRAY));
                        return 0;
                    }
                    sendEvacuateRequest(player, true);
                    return 1;
                })
                .build();
    }

    private void handleShutdownIntent(MessageEnvelope envelope) {
        try {
            ShutdownIntentMessage message = objectMapper.treeToValue(envelope.payload(), ShutdownIntentMessage.class);
            message.validate();
            if (!isTargeted(message.getServices())) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> applyIntent(message));
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to process shutdown intent: " + exception.getMessage());
        }
    }

    private boolean isTargeted(List<String> services) {
        if (services == null || services.isEmpty()) {
            return false;
        }
        String serverId = serverIdentifier.getServerId();
        return serverId != null && services.stream().anyMatch(serverId::equalsIgnoreCase);
    }

    private void applyIntent(ShutdownIntentMessage message) {
        if (message.isCancelled()) {
            if (currentContext != null && currentContext.intentId.equals(message.getId())) {
                plugin.getLogger().info("Shutdown intent " + message.getId() + " cancelled by registry");
                cancelContext();
            }
            return;
        }

        if (currentContext != null && !currentContext.intentId.equals(message.getId())) {
            plugin.getLogger().info("Superseding shutdown intent " + currentContext.intentId + " with " + message.getId());
            cancelContext();
        }

        currentContext = new EvacuationContext(message);
        shuttingDown.set(false);
        startCountdown();
        publishPhase(ShutdownIntentUpdateMessage.Phase.EVACUATE);
        Bukkit.getOnlinePlayers().forEach(player -> showInitialTitle(player, message));
        sendCountdownBroadcast(currentContext.secondsRemaining);
    }

    private void cancelContext() {
        if (currentContext != null) {
            if (currentContext.countdownTask != null) {
                currentContext.countdownTask.cancel();
            }
            if (currentContext.shutdownTask != null) {
                currentContext.shutdownTask.cancel();
            }
            if (renderingPipeline != null) {
                renderingPipeline.clearHeaderOverride();
            }
            currentContext = null;
        }
    }

    private void startCountdown() {
        if (currentContext == null) {
            return;
        }
        if (renderingPipeline != null) {
            renderingPipeline.setHeaderOverride(formatHeaderLine(currentContext.secondsRemaining));
        }
        currentContext.countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (currentContext == null) {
                return;
            }
            int secondsBeforeTick = currentContext.secondsRemaining;
            if (secondsBeforeTick <= 0) {
                beginEviction();
                return;
            }
            currentContext.secondsRemaining = secondsBeforeTick - 1;
            if (currentContext.secondsRemaining <= 0) {
                beginEviction();
                return;
            }
            if (renderingPipeline != null) {
                renderingPipeline.setHeaderOverride(formatHeaderLine(currentContext.secondsRemaining));
            }
            if (secondsBeforeTick == FINAL_REMINDER_SECOND) {
                sendCountdownBroadcast(secondsBeforeTick);
            }
        }, 20L, 20L);
    }

    private void beginEviction() {
        if (currentContext == null || !shuttingDown.compareAndSet(false, true)) {
            return;
        }
        if (currentContext.countdownTask != null) {
            currentContext.countdownTask.cancel();
        }
        publishPhase(ShutdownIntentUpdateMessage.Phase.EVICT);
        String destination = currentContext.fallbackFamily != null && !currentContext.fallbackFamily.isBlank()
                ? currentContext.fallbackFamily
                : "lobby";
        Component evacuateNotice = Component.text("Evacuating you to " + destination + "...", NamedTextColor.GRAY);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(evacuateNotice);
            sendEvacuateRequest(player, false);
        }

        currentContext.shutdownTask = Bukkit.getScheduler().runTaskLater(plugin, this::finalizeShutdown, EVICT_BUFFER.getSeconds() * 20L);
    }

    private void finalizeShutdown() {
        if (currentContext == null) {
            return;
        }
        publishPhase(ShutdownIntentUpdateMessage.Phase.SHUTDOWN);
        kickRemainingPlayers();
        if (renderingPipeline != null) {
            renderingPipeline.clearHeaderOverride();
        }
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getServer().shutdown());
    }

    private void publishPhase(ShutdownIntentUpdateMessage.Phase phase) {
        if (currentContext == null) {
            return;
        }
        ShutdownIntentUpdateMessage update = new ShutdownIntentUpdateMessage();
        update.setIntentId(currentContext.intentId);
        update.setServiceId(serverIdentifier.getServerId());
        update.setPhase(phase);
        if (phase == ShutdownIntentUpdateMessage.Phase.EVACUATE) {
            update.setPlayerIds(Bukkit.getOnlinePlayers().stream()
                    .map(Player::getUniqueId)
                    .toList());
        }
        messageBus.broadcast(ChannelConstants.REGISTRY_SHUTDOWN_UPDATE, update);
    }

    private void sendEvacuateRequest(Player player, boolean manual) {
        if (currentContext == null) {
            player.sendMessage(Component.text("No evacuation in progress.", NamedTextColor.GRAY));
            return;
        }
        PlayerRouteRegistry registry = resolveRouteRegistry();
        String proxyId = registry != null
                ? registry.get(player.getUniqueId())
                .map(RouteAssignment::proxyId)
                .orElse(null)
                : null;
        if ((proxyId == null || proxyId.isBlank()) && lifecycleFeature != null) {
            proxyId = lifecycleFeature.getCurrentProxyId().orElse(null);
        }
        if ((proxyId == null || proxyId.isBlank())) {
            proxyId = resolveProxyIdFromSession(player.getUniqueId());
        }
        if (proxyId == null || proxyId.isBlank()) {
            player.sendMessage(Component.text("Unable to evacuate right now (proxy unknown).", NamedTextColor.RED));
            return;
        }

        PlayerSlotRequest request = new PlayerSlotRequest();
        request.setPlayerId(player.getUniqueId());
        request.setPlayerName(player.getName());
        request.setProxyId(proxyId);
        request.setFamilyId(currentContext.fallbackFamily);
        Map<String, String> metadata = request.getMetadata();
        metadata.put("source", "shutdown-evacuate");
        metadata.put("initiator", manual ? player.getName() : "shutdown-handler");
        metadata.put("shutdownIntentId", currentContext.intentId);
        metadata.put("requestedAt", Long.toString(System.currentTimeMillis()));
        messageBus.broadcast(ChannelConstants.REGISTRY_PLAYER_REQUEST, request);
    }

    private String resolveProxyIdFromSession(UUID playerId) {
        if (sessionService == null || playerId == null) {
            return null;
        }
        return sessionService.getActiveSession(playerId)
                .map(PlayerSessionRecord::getMinigames)
                .map(minigames -> {
                    Object active = minigames.get("active");
                    if (active instanceof Map<?, ?> activeMap) {
                        Object proxy = activeMap.get("proxyId");
                        if (proxy == null) {
                            proxy = activeMap.get("proxy");
                        }
                        return proxy != null ? proxy.toString() : null;
                    }
                    return null;
                })
                .filter(proxy -> proxy != null && !proxy.isBlank())
                .orElse(null);
    }

    private PlayerRouteRegistry resolveRouteRegistry() {
        if (routeRegistry != null) {
            return routeRegistry;
        }
        if (container != null) {
            routeRegistry = container.getOptional(PlayerRouteRegistry.class).orElse(null);
        }
        if (routeRegistry == null && ServiceLocatorImpl.getInstance() != null) {
            routeRegistry = ServiceLocatorImpl.getInstance()
                    .findService(PlayerRouteRegistry.class)
                    .orElse(null);
        }
        return routeRegistry;
    }

    private void kickRemainingPlayers() {
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            return;
        }
        Component message = buildKickMessage();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.kick(message);
        }
    }

    private Component buildKickMessage() {
        String fallback = currentContext != null && currentContext.fallbackFamily != null && !currentContext.fallbackFamily.isBlank()
                ? currentContext.fallbackFamily
                : "the lobby";
        Component header = Component.text("Server rebooting now.", NamedTextColor.RED);
        Component instructions = Component.text("Please rejoin via ", NamedTextColor.GRAY)
                .append(Component.text(fallback, NamedTextColor.YELLOW))
                .append(Component.text(".", NamedTextColor.GRAY));
        Component reason = Component.text("Reason: ", NamedTextColor.GRAY)
                .append(Component.text(currentContext != null ? currentContext.reason : "Scheduled maintenance", NamedTextColor.AQUA));
        return header
                .append(Component.newline())
                .append(instructions)
                .append(Component.newline())
                .append(reason);
    }

    private void sendCountdownBroadcast(int secondsRemaining) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            sendCountdownPrompt(player, secondsRemaining);
        }
    }

    private void sendCountdownPrompt(Player player, int secondsRemaining) {
        if (currentContext == null) {
            return;
        }
        Component reasonLine = Component.text("[DAEMON] ", NamedTextColor.DARK_BLUE)
                .append(Component.text("This server will restart soon: ", NamedTextColor.YELLOW))
                .append(Component.text(currentContext.reason, NamedTextColor.AQUA));
        player.sendMessage(reasonLine);

        TextComponent clickable = Component.text("You have ", NamedTextColor.YELLOW)
                .append(Component.text(secondsRemaining + " seconds", NamedTextColor.GREEN))
                .append(Component.text(" to warp out! ", NamedTextColor.YELLOW))
                .append(Component.text("CLICK", NamedTextColor.GREEN, TextDecoration.BOLD, TextDecoration.UNDERLINED)
                        .hoverEvent(HoverEvent.showText(Component.text("Run /evacuate", NamedTextColor.AQUA)))
                        .clickEvent(ClickEvent.runCommand("/evacuate")))
                .append(Component.text(" to warp now!", NamedTextColor.YELLOW));
        player.sendMessage(clickable);
    }

    private void showInitialTitle(Player player, ShutdownIntentMessage message) {
        Component titleComponent = Component.text("SERVER REBOOT!", NamedTextColor.YELLOW)
                .decoration(TextDecoration.BOLD, true);
        Component subtitle = Component.text("Scheduled Reboot ", NamedTextColor.GREEN)
                .append(Component.text("(in ", NamedTextColor.GRAY))
                .append(Component.text(formatTime(message.getCountdownSeconds()), NamedTextColor.YELLOW))
                .append(Component.text(")", NamedTextColor.GRAY));
        Title title = Title.title(titleComponent, subtitle);
        player.showTitle(title);
    }

    private String formatHeaderLine(int seconds) {
        return "&cServer Restarting: " + seconds + "s &f" + serverIdentifier.getServerId();
    }

    private String formatTime(int seconds) {
        if (seconds <= 0) {
            return "0:00";
        }
        int minutes = seconds / 60;
        int rem = seconds % 60;
        String secondsPart = rem < 10 ? "0" + rem : Integer.toString(rem);
        return minutes + ":" + secondsPart;
    }

    private static final class EvacuationContext {
        private final String intentId;
        private final String reason;
        private final String fallbackFamily;
        private final boolean force;
        private int secondsRemaining;
        private BukkitTask countdownTask;
        private BukkitTask shutdownTask;

        private EvacuationContext(ShutdownIntentMessage message) {
            this.intentId = message.getId();
            this.reason = message.getReason() != null ? message.getReason() : "Scheduled Reboot";
            this.fallbackFamily = message.getBackendTransferHint() != null
                    ? message.getBackendTransferHint() : "lobby";
            this.force = message.isForce();
            this.secondsRemaining = Math.max(1, message.getCountdownSeconds());
        }
    }
}
