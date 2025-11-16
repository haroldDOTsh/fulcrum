package sh.harold.fulcrum.velocity.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.maintenance.MaintenanceContext;
import sh.harold.fulcrum.api.maintenance.MaintenanceScope;
import sh.harold.fulcrum.api.maintenance.MaintenanceStatus;
import sh.harold.fulcrum.api.messagebus.*;
import sh.harold.fulcrum.api.messagebus.messages.network.MaintenanceToggleMessage;
import sh.harold.fulcrum.api.network.NetworkConfigService;
import sh.harold.fulcrum.api.network.NetworkProfileView;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.config.ConfigLoader;
import sh.harold.fulcrum.velocity.config.RedisConfig;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;
import sh.harold.fulcrum.velocity.session.LettuceSessionRedisClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Tracks maintenance context broadcasts and denies non-staff logins during network maintenance.
 */
public final class VelocityMaintenanceFeature implements VelocityFeature {
    private static final String NETWORK_SCOPE_KEY = "maintenance:scopes:network";
    private static final String DEFAULT_INFO_LINK = "https://status.fulcrum.gg";
    private final ObjectMapper mapper;
    private ServiceLocator serviceLocator;
    private FulcrumVelocityPlugin plugin;
    private ProxyServer proxyServer;
    private Logger logger;
    private MessageBus messageBus;
    private RankService rankService;
    private NetworkConfigService networkConfigService;
    private LettuceSessionRedisClient redisClient;
    private MaintenanceStateService maintenanceStateService;
    private MessageHandler maintenanceHandler;

    public VelocityMaintenanceFeature() {
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public String getName() {
        return "Maintenance";
    }

    @Override
    public int getPriority() {
        // after rank (26) before motd (30)
        return 28;
    }

    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) {
        this.serviceLocator = serviceLocator;
        this.logger = logger;
        this.plugin = serviceLocator.getRequiredService(FulcrumVelocityPlugin.class);
        this.proxyServer = serviceLocator.getRequiredService(ProxyServer.class);
        this.messageBus = serviceLocator.getService(MessageBus.class).orElse(null);
        this.rankService = serviceLocator.getService(RankService.class).orElse(null);
        this.networkConfigService = serviceLocator.getService(NetworkConfigService.class).orElse(null);
        ConfigLoader configLoader = serviceLocator.getRequiredService(ConfigLoader.class);
        RedisConfig redisConfig = configLoader.getConfig(RedisConfig.class);
        if (redisConfig == null) {
            throw new IllegalStateException("Redis configuration missing; cannot initialise maintenance feature");
        }

        this.redisClient = new LettuceSessionRedisClient(redisConfig, logger);
        this.maintenanceStateService = new VelocityMaintenanceStateService();
        serviceLocator.register(MaintenanceStateService.class, maintenanceStateService);

        loadInitialState();

        proxyServer.getEventManager().register(plugin, this);

        if (messageBus != null) {
            maintenanceHandler = this::handleMaintenanceUpdate;
            messageBus.subscribe(ChannelConstants.REGISTRY_MAINTENANCE_UPDATE, maintenanceHandler);
        } else {
            logger.warn("MessageBus unavailable; maintenance updates will rely solely on Redis state");
        }
    }

    @Override
    public void shutdown() {
        if (messageBus != null && maintenanceHandler != null) {
            messageBus.unsubscribe(ChannelConstants.REGISTRY_MAINTENANCE_UPDATE, maintenanceHandler);
        }
        if (redisClient != null) {
            try {
                redisClient.close();
            } catch (Exception ignored) {
            }
        }
        if (serviceLocator != null) {
            serviceLocator.unregister(MaintenanceStateService.class);
        }
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        Optional<MaintenanceContext> context = maintenanceStateService.getNetworkContext();
        if (context.isEmpty()) {
            return;
        }
        if (playerIsStaff(event.getPlayer().getUniqueId())) {
            return;
        }
        Component denial = buildDenialScreen(context.get());
        event.setResult(ResultedEvent.ComponentResult.denied(denial));
    }

    private void loadInitialState() {
        if (redisClient == null || !redisClient.isAvailable()) {
            return;
        }
        try {
            String payload = redisClient.get(NETWORK_SCOPE_KEY);
            if (payload == null || payload.isBlank()) {
                maintenanceStateService.clearAll();
                return;
            }
            MaintenanceContext context = mapper.readValue(payload, MaintenanceContext.class);
            if (context.isActive()) {
                maintenanceStateService.setNetworkContext(context);
                logger.info("Maintenance context {} restored during startup", context.shortId());
            } else {
                maintenanceStateService.clearAll();
            }
        } catch (Exception ex) {
            logger.warn("Failed to load maintenance state from Redis", ex);
        }
    }

    private void handleMaintenanceUpdate(MessageEnvelope envelope) {
        try {
            MaintenanceToggleMessage message = MessageTypeRegistry.getInstance()
                    .deserializeToClass(envelope.payload(), MaintenanceToggleMessage.class);
            if (message == null || message.getScope() != MaintenanceScope.NETWORK) {
                return;
            }
            MaintenanceContext context = new MaintenanceContext(
                    message.getContextId(),
                    message.getScope(),
                    message.getStatus(),
                    message.getUpdatedAt(),
                    message.getActor(),
                    message.getExpiresAt()
            );
            if (message.getStatus() == MaintenanceStatus.ON) {
                maintenanceStateService.setNetworkContext(context);
                logger.info("Maintenance enabled (context {})", context.shortId());
            } else {
                maintenanceStateService.clearNetworkContext(context.id());
                logger.info("Maintenance disabled (context {})", context.shortId());
            }
        } catch (Exception ex) {
            logger.warn("Failed to process maintenance update", ex);
        }
    }

    private Component buildDenialScreen(MaintenanceContext context) {
        String serverName = resolveServerName();
        String link = resolveInfoLink();

        Component lineOne = Component.text()
                .append(Component.text("We are sorry but ", NamedTextColor.RED))
                .append(Component.text(serverName, NamedTextColor.AQUA))
                .append(Component.text(" is currently down for maintenance.", NamedTextColor.RED))
                .build();

        Component lineTwo = Component.text()
                .append(Component.text("For more information: ", NamedTextColor.RED))
                .append(Component.text(link, NamedTextColor.AQUA))
                .build();

        Optional<Component> timerLine = buildTimerLine(context.expiresAt());

        return timerLine
                .map(lineThree -> Component.join(Component.newline(), lineOne, lineTwo, lineThree))
                .orElseGet(() -> Component.join(Component.newline(), lineOne, lineTwo));
    }

    private Optional<Component> buildTimerLine(Instant expiresAt) {
        if (expiresAt == null) {
            return Optional.empty();
        }
        Duration remaining = Duration.between(Instant.now(), expiresAt);
        if (remaining.isNegative()) {
            return Optional.empty();
        }
        String timer = formatDuration(remaining);
        Component line = Component.text()
                .append(Component.text("Check back in: ", NamedTextColor.RED))
                .append(Component.text(timer, NamedTextColor.AQUA))
                .build();
        return Optional.of(line);
    }

    private String formatDuration(Duration duration) {
        long totalSeconds = duration.getSeconds();
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        StringBuilder builder = new StringBuilder();
        if (hours > 0) {
            builder.append(hours).append("h ");
        }
        if (minutes > 0 || hours > 0) {
            builder.append(minutes).append("m ");
        }
        builder.append(seconds).append("s");
        return builder.toString().trim();
    }

    private boolean playerIsStaff(UUID playerId) {
        if (rankService == null || playerId == null) {
            return true;
        }
        try {
            Rank rank = rankService.getEffectiveRankSync(playerId);
            return rank != null && rank.isStaff();
        } catch (Exception ex) {
            logger.debug("Unable to resolve rank for {}", playerId, ex);
            return false;
        }
    }

    private String resolveServerName() {
        if (networkConfigService == null) {
            return "Fulcrum Network";
        }
        try {
            NetworkProfileView profile = networkConfigService.getActiveProfile();
            return profile.getString("info.serverName").orElse("Fulcrum Network");
        } catch (Exception ex) {
            logger.debug("Failed to resolve server name from network config", ex);
            return "Fulcrum Network";
        }
    }

    private String resolveInfoLink() {
        String override = System.getenv("MAINTENANCE_INFO_LINK");
        if (override != null && !override.isBlank()) {
            return override;
        }
        if (networkConfigService != null) {
            try {
                NetworkProfileView profile = networkConfigService.getActiveProfile();
                Optional<String> link = profile.getString("info.websiteLink").filter(value -> !value.isBlank());
                if (link.isPresent()) {
                    return link.get();
                }
                return profile.getString("info.discordLink").filter(value -> !value.isBlank()).orElse(DEFAULT_INFO_LINK);
            } catch (Exception ex) {
                logger.debug("Failed to resolve maintenance info link from network config", ex);
            }
        }
        return DEFAULT_INFO_LINK;
    }
}
