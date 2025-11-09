package sh.harold.fulcrum.fundamentals.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.api.data.schema.SchemaDefinition;
import sh.harold.fulcrum.api.data.schema.SchemaRegistry;
import sh.harold.fulcrum.api.lifecycle.ServerIdentifier;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.api.module.FulcrumEnvironment;
import sh.harold.fulcrum.data.playtime.PlaytimeTracker;
import sh.harold.fulcrum.fundamentals.slot.SimpleSlotOrchestrator;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;
import sh.harold.fulcrum.runtime.redis.LettuceRedisOperations;
import sh.harold.fulcrum.runtime.redis.RedisConfig;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Bootstraps the player session service backed by Redis.
 */
public class PlayerSessionFeature implements PluginFeature {

    private LettuceRedisOperations redisOperations;
    private PlayerSessionService sessionService;
    private PlayerReservationService reservationService;
    private MessageBus messageBus;
    private MessageHandler reservationHandler;
    private DependencyContainer container;
    private Logger logger;
    private PlayerSessionLogRepository sessionLogRepository;
    private JavaPlugin plugin;
    private PlaytimeTracker playtimeTracker;
    private final Map<String, PlayerSessionService.ServerSlotAttachment> slotAttachments = new ConcurrentHashMap<>();
    private SimpleSlotOrchestrator slotOrchestrator;

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        this.container = container;
        this.logger = plugin.getLogger();

        RedisConfig redisConfig = loadRedisConfig(plugin);
        if (redisConfig != null) {
            try {
                redisOperations = new LettuceRedisOperations(redisConfig);
                container.register(LettuceRedisOperations.class, redisOperations);
                if (ServiceLocatorImpl.getInstance() != null) {
                    ServiceLocatorImpl.getInstance().registerService(LettuceRedisOperations.class, redisOperations);
                }
            } catch (RuntimeException ex) {
                logger.warning("Failed to initialise Redis session backend, falling back to local cache: " + ex.getMessage());
                redisOperations = null;
            }
        } else {
            logger.warning("Redis disabled in configuration; player sessions will not be shared across nodes.");
        }

        ServerIdentifier resolvedIdentifier = resolveServerIdentifier(container);
        String serverId = resolvedIdentifier != null && resolvedIdentifier.getServerId() != null
                ? resolvedIdentifier.getServerId()
                : plugin.getServer().getName();
        String environment = resolveEnvironment(resolvedIdentifier);
        ObjectMapper mapper = new ObjectMapper();
        playtimeTracker = new PlaytimeTracker(plugin.getLogger());
        sessionService = new PlayerSessionService(redisOperations, mapper, serverId, environment, resolvedIdentifier, playtimeTracker);

        container.register(PlayerSessionService.class, sessionService);
        container.register(PlaytimeTracker.class, playtimeTracker);
        if (ServiceLocatorImpl.getInstance() != null) {
            ServiceLocatorImpl.getInstance().registerService(PlayerSessionService.class, sessionService);
            ServiceLocatorImpl.getInstance().registerService(PlaytimeTracker.class, playtimeTracker);
        }

        if (isStaticServiceNode(environment, resolvedIdentifier)) {
            slotOrchestrator = resolveSlotOrchestrator(container);
            wireSlotFamilyTracking();
        }

        messageBus = container.getOptional(MessageBus.class).orElse(null);
        if (messageBus != null) {
            reservationService = new PlayerReservationService(logger, messageBus);
            reservationHandler = reservationService::handleReservationRequest;
            messageBus.subscribe(ChannelConstants.PLAYER_RESERVATION_REQUEST, reservationHandler);
            container.register(PlayerReservationService.class, reservationService);
            if (ServiceLocatorImpl.getInstance() != null) {
                ServiceLocatorImpl.getInstance().registerService(PlayerReservationService.class, reservationService);
            }
        } else {
            logger.warning("MessageBus unavailable; reservation requests will be skipped.");
        }

        container.getOptional(PostgresConnectionAdapter.class).ifPresent(adapter -> {
            if (ensureSessionSchema(adapter)) {
                sessionLogRepository = new PlayerSessionLogRepository(adapter, logger);
                container.register(PlayerSessionLogRepository.class, sessionLogRepository);
                if (ServiceLocatorImpl.getInstance() != null) {
                    ServiceLocatorImpl.getInstance().registerService(PlayerSessionLogRepository.class, sessionLogRepository);
                }
            } else {
                logger.warning("Player session schema unavailable; session logs disabled");
            }
        });
        logger.info("PlayerSessionService initialised (Redis " + (redisOperations != null ? "ENABLED" : "DISABLED") + ")");
    }

    @Override
    public void shutdown() {
        if (ServiceLocatorImpl.getInstance() != null) {
            ServiceLocatorImpl.getInstance().unregisterService(PlayerSessionService.class);
            ServiceLocatorImpl.getInstance().unregisterService(PlayerSessionLogRepository.class);
            ServiceLocatorImpl.getInstance().unregisterService(PlayerReservationService.class);
            ServiceLocatorImpl.getInstance().unregisterService(PlaytimeTracker.class);
        }
        if (container != null) {
            container.unregister(PlayerSessionService.class);
            container.unregister(PlayerSessionLogRepository.class);
            container.unregister(PlayerReservationService.class);
            container.unregister(PlaytimeTracker.class);
        }
        slotAttachments.values().forEach(attachment -> {
            if (attachment != null) {
                attachment.close();
            }
        });
        slotAttachments.clear();
        if (messageBus != null && reservationHandler != null) {
            messageBus.unsubscribe(ChannelConstants.PLAYER_RESERVATION_REQUEST, reservationHandler);
        }
        if (sessionService != null) {
            sessionService.clearLocalCache();
        }
        playtimeTracker = null;
        if (redisOperations != null) {
            try {
                redisOperations.close();
            } catch (Exception e) {
                logger.warning("Failed to close Redis connection gracefully: " + e.getMessage());
            }
            if (ServiceLocatorImpl.getInstance() != null) {
                ServiceLocatorImpl.getInstance().unregisterService(LettuceRedisOperations.class);
            }
            if (container != null) {
                container.unregister(LettuceRedisOperations.class);
            }
        }
        logger.info("PlayerSessionService shut down");
    }

    @Override
    public int getPriority() {
        return 15; // After DataAPI (10) and before features that rely on session state
    }

    private boolean ensureSessionSchema(PostgresConnectionAdapter adapter) {
        try {
            SchemaRegistry.ensureSchema(
                    adapter,
                    SchemaDefinition.fromResource(
                            "player-sessions-001",
                            "Create player session logging table",
                            plugin.getClass().getClassLoader(),
                            "migrations/player_sessions.sql"
                    )
            );
            SchemaRegistry.ensureSchema(
                    adapter,
                    SchemaDefinition.fromResource(
                            "player-session-segments-001",
                            "Create player session segment table",
                            plugin.getClass().getClassLoader(),
                            "migrations/player_session_segments.sql"
                    )
            );
            return true;
        } catch (Exception ex) {
            logger.warning("Failed to ensure player session schema: " + ex.getMessage());
            return false;
        }
    }

    private ServerIdentifier resolveServerIdentifier(DependencyContainer container) {
        return container.getOptional(ServerIdentifier.class)
                .orElseGet(() -> ServiceLocatorImpl.getInstance() != null
                        ? ServiceLocatorImpl.getInstance()
                        .findService(ServerIdentifier.class)
                        .orElse(null)
                        : null);
    }

    private String resolveEnvironment(ServerIdentifier identifier) {
        if (identifier != null) {
            String role = identifier.getRole();
            if (role != null && !role.isBlank()) {
                return role;
            }
        }
        try {
            return FulcrumEnvironment.getCurrent();
        } catch (IllegalStateException ignored) {
            return "unknown";
        }
    }

    private SimpleSlotOrchestrator resolveSlotOrchestrator(DependencyContainer container) {
        return container.getOptional(SimpleSlotOrchestrator.class)
                .orElseGet(() -> ServiceLocatorImpl.getInstance() != null
                        ? ServiceLocatorImpl.getInstance()
                        .findService(SimpleSlotOrchestrator.class)
                        .orElse(null)
                        : null);
    }

    private void wireSlotFamilyTracking() {
        if (slotOrchestrator == null || sessionService == null) {
            return;
        }
        slotOrchestrator.addProvisionListener(slot -> {
            if (slot == null || slot.familyId() == null) {
                return;
            }
            PlayerSessionService.ServerSlotAttachment attachment = sessionService.attachToSlot(slot);
            PlayerSessionService.ServerSlotAttachment previous = slotAttachments.put(slot.slotId(), attachment);
            if (previous != null) {
                previous.close();
            }
        });
    }

    private boolean isStaticServiceNode(String environment, ServerIdentifier identifier) {
        String role = environment != null ? environment : identifier != null ? identifier.getRole() : null;
        if (role != null && role.equalsIgnoreCase("game")) {
            return false;
        }
        String type = identifier != null ? identifier.getType() : null;
        return type == null || (!type.equalsIgnoreCase("MINI") && !type.equalsIgnoreCase("MEGA"));
    }

    private RedisConfig loadRedisConfig(JavaPlugin plugin) {
        File configFile = new File(plugin.getDataFolder(), "database-config.yml");
        if (!configFile.exists()) {
            plugin.saveResource("database-config.yml", false);
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
        ConfigurationSection redisSection = yaml.getConfigurationSection("redis");
        if (redisSection == null) {
            return null;
        }

        boolean enabled = redisSection.getBoolean("enabled", true);
        if (!enabled) {
            return null;
        }

        RedisConfig.Builder builder = RedisConfig.builder()
                .host(redisSection.getString("host", "localhost"))
                .port(redisSection.getInt("port", 6379))
                .database(redisSection.getInt("database", 0));

        String password = redisSection.getString("password", "");
        if (password != null && !password.isBlank()) {
            builder.password(password);
        }

        ConfigurationSection pool = redisSection.getConfigurationSection("connection-pool");
        if (pool != null) {
            builder
                    .minIdleConnections(pool.getInt("min-idle", 1))
                    .maxIdleConnections(pool.getInt("max-idle", 8))
                    .maxConnections(pool.getInt("max-total", 16));
        }

        return builder.build();
    }
}
