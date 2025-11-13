package sh.harold.fulcrum.velocity.fundamentals.shutdown;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import io.lettuce.core.RedisCommandInterruptedException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.api.messagebus.messages.ProxyAnnouncementMessage;
import sh.harold.fulcrum.api.messagebus.messages.ShutdownIntentMessage;
import sh.harold.fulcrum.api.messagebus.messages.ShutdownIntentUpdateMessage;
import sh.harold.fulcrum.api.network.NetworkConfigService;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.fundamentals.lifecycle.VelocityServerLifecycleFeature;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles shutdown intents for Velocity proxies (chat-only experience).
 */
public final class ProxyShutdownFeature implements VelocityFeature {
    private static final Duration EVICT_BUFFER = Duration.ofSeconds(3);
    private static final Duration ENDPOINT_STALE = Duration.ofSeconds(45);
    private static final int EXTRA_COUNTDOWN_SECONDS = 8;
    private final Map<String, ProxyEndpoint> proxyEndpoints = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private ProxyServer proxy;
    private Logger logger;
    private MessageBus messageBus;
    private VelocityServerLifecycleFeature lifecycleFeature;
    private FulcrumVelocityPlugin plugin;
    private String proxyId;
    private static final String NETWORK_FALLBACK_IP = "???";
    private volatile boolean shuttingDown;
    private String serverIp = NETWORK_FALLBACK_IP;
    private EvacuationContext context;
    private MessageHandler shutdownHandler;
    private MessageHandler announcementHandler;
    private MessageHandler removalHandler;

    @Override
    public String getName() {
        return "ProxyShutdown";
    }

    @Override
    public int getPriority() {
        return 75;
    }

    @Override
    public void initialize(ServiceLocator locator, Logger logger) throws Exception {
        this.logger = logger;
        this.proxy = locator.getRequiredService(ProxyServer.class);
        this.messageBus = locator.getRequiredService(MessageBus.class);
        this.lifecycleFeature = locator.getRequiredService(VelocityServerLifecycleFeature.class);
        this.plugin = locator.getRequiredService(FulcrumVelocityPlugin.class);
        this.proxyId = lifecycleFeature.getCurrentProxyId().orElse(null);

        locator.getService(NetworkConfigService.class)
                .flatMap(service -> service.getString("serverIp"))
                .ifPresentOrElse(
                        ip -> this.serverIp = ip,
                        () -> this.serverIp = NETWORK_FALLBACK_IP
                );

        shutdownHandler = this::handleShutdownIntent;
        announcementHandler = this::handleProxyAnnouncement;
        removalHandler = envelope -> {
            try {
                ProxyAnnouncementMessage message = objectMapper.treeToValue(envelope.payload(), ProxyAnnouncementMessage.class);
                if (message != null) {
                    proxyEndpoints.remove(message.getProxyId());
                }
            } catch (Exception exception) {
                logger.warn("Failed to process proxy removal payload", exception);
            }
        };

        messageBus.subscribe(ChannelConstants.REGISTRY_SHUTDOWN_INTENT, shutdownHandler);
        messageBus.subscribe(ChannelConstants.PROXY_ANNOUNCEMENT, announcementHandler);
        messageBus.subscribe(ChannelConstants.PROXY_SHUTDOWN, removalHandler);
    }

    @Override
    public void shutdown() {
        if (messageBus != null) {
            if (shutdownHandler != null) {
                messageBus.unsubscribe(ChannelConstants.REGISTRY_SHUTDOWN_INTENT, shutdownHandler);
            }
            if (announcementHandler != null) {
                messageBus.unsubscribe(ChannelConstants.PROXY_ANNOUNCEMENT, announcementHandler);
            }
            if (removalHandler != null) {
                messageBus.unsubscribe(ChannelConstants.PROXY_SHUTDOWN, removalHandler);
            }
        }
        cancelContext();
    }

    private void handleShutdownIntent(MessageEnvelope envelope) {
        try {
            ShutdownIntentMessage message = objectMapper.treeToValue(envelope.payload(), ShutdownIntentMessage.class);
            message.validate();
            if (!isTargeted(message.getServices())) {
                return;
            }
            proxy.getScheduler().buildTask(plugin, () -> applyIntent(message)).schedule();
        } catch (Exception exception) {
            logger.warn("Failed to process proxy shutdown intent", exception);
        }
    }

    private boolean isTargeted(List<String> services) {
        if (services == null || services.isEmpty()) {
            return false;
        }
        String id = ensureProxyId();
        return id != null && services.stream().anyMatch(id::equalsIgnoreCase);
    }

    private String ensureProxyId() {
        if (proxyId == null) {
            proxyId = lifecycleFeature.getCurrentProxyId().orElse(null);
        }
        return proxyId;
    }

    private void applyIntent(ShutdownIntentMessage message) {
        if (message.isCancelled()) {
            if (context != null && context.intentId.equals(message.getId())) {
                logger.info("Shutdown intent {} cancelled", message.getId());
                cancelContext();
            }
            return;
        }

        if (context != null && !context.intentId.equals(message.getId())) {
            cancelContext();
        }

        context = new EvacuationContext(message);
        publishPhase(ShutdownIntentUpdateMessage.Phase.EVACUATE);
        broadcastWarning();
        startCountdown();
    }

    private void cancelContext() {
        if (context != null) {
            if (context.countdownTask != null) {
                context.countdownTask.cancel();
            }
            if (context.shutdownTask != null) {
                context.shutdownTask.cancel();
            }
            context = null;
        }
    }

    private void startCountdown() {
        if (context == null) {
            return;
        }
        context.countdownTask = proxy.getScheduler().buildTask(plugin, () -> {
            if (context == null) {
                return;
            }
            context.secondsRemaining--;
            if (context.secondsRemaining <= 0) {
                beginEviction();
                return;
            }
            if (!context.fifteenSecondWarningSent && context.secondsRemaining <= 15) {
                context.fifteenSecondWarningSent = true;
                broadcastWarning();
            }
        }).repeat(Duration.ofSeconds(1)).schedule();
    }

    private void beginEviction() {
        if (context == null) {
            return;
        }
        if (context.countdownTask != null) {
            context.countdownTask.cancel();
        }
        publishPhase(ShutdownIntentUpdateMessage.Phase.EVICT);
        transferPlayers();
        context.shutdownTask = proxy.getScheduler().buildTask(plugin, this::finalizeShutdown)
                .delay(EVICT_BUFFER)
                .schedule();
    }

    private void finalizeShutdown() {
        publishPhase(ShutdownIntentUpdateMessage.Phase.SHUTDOWN);
        shuttingDown = true;
        proxy.shutdown(Component.text("Proxy restarting for maintenance.", NamedTextColor.RED));
    }

    private void publishPhase(ShutdownIntentUpdateMessage.Phase phase) {
        if (context == null) {
            return;
        }
        String currentId = ensureProxyId();
        if (currentId == null) {
            logger.warn("Unable to publish shutdown phase {}; proxyId unknown", phase);
            return;
        }
        ShutdownIntentUpdateMessage update = new ShutdownIntentUpdateMessage();
        update.setIntentId(context.intentId);
        update.setServiceId(currentId);
        update.setPhase(phase);
        if (phase == ShutdownIntentUpdateMessage.Phase.EVACUATE) {
            update.setPlayerIds(proxy.getAllPlayers().stream()
                    .map(Player::getUniqueId)
                    .toList());
        }
        try {
            messageBus.broadcast(ChannelConstants.REGISTRY_SHUTDOWN_UPDATE, update);
        } catch (RedisCommandInterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (shuttingDown) {
                logger.debug("Suppressed shutdown phase {} publish; proxy is already stopping.", phase);
            } else {
                logger.warn("Interrupted while publishing shutdown phase {}", phase, interrupted);
            }
        }
    }

    private void broadcastWarning() {
        if (context == null) {
            return;
        }
        Component spacer = Component.empty();
        Component lineOne = MINI_MESSAGE.deserialize(
                "<gold><obfuscated>AA</obfuscated></gold><reset> <red>This proxy is restarting soon.</red>");
        Component lineTwo = MINI_MESSAGE.deserialize(
                "<gold><obfuscated>AA</obfuscated></gold><reset> <red>Please reconnect to </red><aqua>" + serverIp + "</aqua><red>.</red>");
        for (Player player : proxy.getAllPlayers()) {
            player.sendMessage(spacer);
            player.sendMessage(lineOne);
            player.sendMessage(lineTwo);
            player.sendMessage(spacer);
        }
    }

    private void transferPlayers() {
        ProxyEndpoint fallback = findAlternateProxy().orElse(null);
        for (Player player : proxy.getAllPlayers()) {
            if (fallback != null) {
                logger.info("Transferring {} to {}", player.getUsername(), fallback.proxyId);
                player.transferToHost(fallback.socketAddress());
                player.sendMessage(Component.text("Transferring you to another proxy…", NamedTextColor.YELLOW));
            } else {
                player.disconnect(Component.text("Proxy restarting - please rejoin " + serverIp, NamedTextColor.RED));
            }
        }
    }

    private Optional<ProxyEndpoint> findAlternateProxy() {
        pruneStaleEndpoints();
        String currentId = ensureProxyId();
        return proxyEndpoints.values().stream()
                .filter(endpoint -> !endpoint.proxyId.equalsIgnoreCase(currentId))
                .min(Comparator.comparingInt(ProxyEndpoint::playerCount));
    }

    private void pruneStaleEndpoints() {
        long cutoff = System.currentTimeMillis() - ENDPOINT_STALE.toMillis();
        proxyEndpoints.values().removeIf(endpoint -> endpoint.updatedAt < cutoff);
    }

    public void onProxyIdUpdated(String newProxyId) {
        if (newProxyId == null || newProxyId.isBlank()) {
            return;
        }
        this.proxyId = newProxyId;
        logger.info("ProxyShutdownFeature updated proxyId to {}", newProxyId);
    }

    private void handleProxyAnnouncement(MessageEnvelope envelope) {
        try {
            ProxyAnnouncementMessage message = objectMapper.treeToValue(envelope.payload(), ProxyAnnouncementMessage.class);
            if (message == null || message.getProxyId() == null) {
                return;
            }
            if (proxyId != null && proxyId.equalsIgnoreCase(message.getProxyId())) {
                return;
            }
            if (message.getAddress() == null || message.getAddress().isBlank()) {
                return;
            }
            String[] parts = message.getAddress().split(":");
            if (parts.length != 2) {
                return;
            }
            try {
                String host = parts[0];
                int port = Integer.parseInt(parts[1]);
                proxyEndpoints.put(message.getProxyId(), new ProxyEndpoint(message.getProxyId(), host, port, message.getCurrentPlayerCount(), System.currentTimeMillis()));
            } catch (NumberFormatException ignored) {
            }
        } catch (Exception exception) {
            logger.warn("Failed to process proxy announcement", exception);
        }
    }

    private static final class EvacuationContext {
        private final String intentId;
        private final boolean force;
        private int secondsRemaining;
        private ScheduledTask countdownTask;
        private ScheduledTask shutdownTask;
        private boolean fifteenSecondWarningSent;

        private EvacuationContext(ShutdownIntentMessage message) {
            this.intentId = message.getId();
            this.secondsRemaining = Math.max(1, message.getCountdownSeconds()) + EXTRA_COUNTDOWN_SECONDS;
            this.force = message.isForce();
        }
    }

    private record ProxyEndpoint(String proxyId, String host, int port, int playerCount, long updatedAt) {
        InetSocketAddress socketAddress() {
            return new InetSocketAddress(host, port);
        }
    }
}
