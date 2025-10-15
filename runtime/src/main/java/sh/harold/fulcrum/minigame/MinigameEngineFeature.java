package sh.harold.fulcrum.minigame;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.data.impl.mongodb.MongoConnectionAdapter;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.fundamentals.actionflag.ActionFlagService;
import sh.harold.fulcrum.fundamentals.session.PlayerReservationService;
import sh.harold.fulcrum.fundamentals.session.PlayerSessionService;
import sh.harold.fulcrum.fundamentals.slot.SimpleSlotOrchestrator;
import sh.harold.fulcrum.fundamentals.world.WorldManager;
import sh.harold.fulcrum.fundamentals.world.WorldService;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;
import sh.harold.fulcrum.minigame.command.StateMachineCommand;
import sh.harold.fulcrum.minigame.data.MinigameDataRegistry;
import sh.harold.fulcrum.minigame.data.MongoMinigameDataRegistry;
import sh.harold.fulcrum.minigame.environment.MinigameEnvironmentService;
import sh.harold.fulcrum.minigame.listener.MatchDamageListener;
import sh.harold.fulcrum.minigame.listener.PlayerRoutingListener;
import sh.harold.fulcrum.minigame.listener.SpectatorListener;
import sh.harold.fulcrum.minigame.match.MatchHistoryWriter;
import sh.harold.fulcrum.minigame.match.MatchLogWriter;
import sh.harold.fulcrum.minigame.routing.PlayerRouteRegistry;

import java.util.logging.Logger;

/**
 * Bootstraps the minigame engine and registers it in the service locator.
 */
public class MinigameEngineFeature implements PluginFeature {
    private MinigameEngine engine;
    private GameManager gameManager;
    private PlayerRouteRegistry routeRegistry;
    private PlayerRoutingListener routingListener;
    private MinigameEnvironmentService environmentService;
    private MinigameDataRegistry dataRegistry;
    private MatchHistoryWriter matchHistoryWriter;
    private MatchLogWriter matchLogWriter;
    private DependencyContainer containerRef;

    @Override
    public int getPriority() {
        return 220; // Runs after world feature so environment services are available
    }

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.containerRef = container;
        routeRegistry = new PlayerRouteRegistry();
        container.register(PlayerRouteRegistry.class, routeRegistry);
        environmentService = createEnvironmentService(plugin, container);
        SimpleSlotOrchestrator orchestrator = container.getOptional(SimpleSlotOrchestrator.class)
                .orElseGet(() -> ServiceLocatorImpl.getInstance() != null
                        ? ServiceLocatorImpl.getInstance().findService(SimpleSlotOrchestrator.class).orElse(null)
                        : null);
        if (orchestrator == null) {
            plugin.getLogger().warning("SimpleSlotOrchestrator unavailable; provisioning events will be deferred.");
        }
        ActionFlagService actionFlagService = container.getOptional(ActionFlagService.class)
                .orElseGet(() -> ServiceLocatorImpl.getInstance() != null
                        ? ServiceLocatorImpl.getInstance().findService(ActionFlagService.class).orElse(null)
                        : null);
        if (actionFlagService == null) {
            plugin.getLogger().warning("ActionFlagService unavailable; matches will start without flag enforcement.");
        }
        PlayerSessionService sessionService = container.getOptional(PlayerSessionService.class)
                .orElseGet(() -> ServiceLocatorImpl.getInstance() != null
                        ? ServiceLocatorImpl.getInstance().findService(PlayerSessionService.class).orElse(null)
                        : null);
        if (sessionService == null) {
            plugin.getLogger().warning("PlayerSessionService unavailable; session tracking features will be disabled.");
        }

        MongoConnectionAdapter mongoAdapter = container.getOptional(MongoConnectionAdapter.class)
                .orElseGet(() -> ServiceLocatorImpl.getInstance() != null
                        ? ServiceLocatorImpl.getInstance().findService(MongoConnectionAdapter.class).orElse(null)
                        : null);
        if (mongoAdapter != null) {
            dataRegistry = new MongoMinigameDataRegistry(mongoAdapter.getMongoDatabase(), new ObjectMapper(), plugin.getLogger());
            container.register(MinigameDataRegistry.class, dataRegistry);
            if (ServiceLocatorImpl.getInstance() != null) {
                ServiceLocatorImpl.getInstance().registerService(MinigameDataRegistry.class, dataRegistry);
            }
        } else {
            plugin.getLogger().warning("Mongo adapter unavailable; per-minigame player data will be skipped.");
        }

        PostgresConnectionAdapter postgresAdapter = container.getOptional(PostgresConnectionAdapter.class)
                .orElseGet(() -> ServiceLocatorImpl.getInstance() != null
                        ? ServiceLocatorImpl.getInstance().findService(PostgresConnectionAdapter.class).orElse(null)
                        : null);
        if (postgresAdapter != null) {
            matchHistoryWriter = new MatchHistoryWriter(postgresAdapter, plugin.getLogger());
            matchLogWriter = new MatchLogWriter(postgresAdapter, plugin.getLogger());
            container.register(MatchHistoryWriter.class, matchHistoryWriter);
            container.register(MatchLogWriter.class, matchLogWriter);
            if (ServiceLocatorImpl.getInstance() != null) {
                ServiceLocatorImpl.getInstance().registerService(MatchHistoryWriter.class, matchHistoryWriter);
                ServiceLocatorImpl.getInstance().registerService(MatchLogWriter.class, matchLogWriter);
            }
        } else {
            plugin.getLogger().warning("PostgreSQL adapter unavailable; match history logging disabled.");
        }

        engine = new MinigameEngine(plugin, routeRegistry, environmentService, orchestrator, actionFlagService, sessionService, dataRegistry, matchHistoryWriter, matchLogWriter);
        if (orchestrator != null) {
            orchestrator.addProvisionListener(engine::handleProvisionedSlot);
        }
        gameManager = new GameManager(engine);
        container.register(MinigameEngine.class, engine);
        container.register(GameManager.class, gameManager);
        if (environmentService != null) {
            container.register(MinigameEnvironmentService.class, environmentService);
        }
        if (ServiceLocatorImpl.getInstance() != null) {
            ServiceLocatorImpl.getInstance().registerService(MinigameEngine.class, engine);
            ServiceLocatorImpl.getInstance().registerService(GameManager.class, gameManager);
            ServiceLocatorImpl.getInstance().registerService(PlayerRouteRegistry.class, routeRegistry);
            if (environmentService != null) {
                ServiceLocatorImpl.getInstance().registerService(MinigameEnvironmentService.class, environmentService);
            }
        }
        Bukkit.getPluginManager().registerEvents(new SpectatorListener(), plugin);
        Bukkit.getPluginManager().registerEvents(new MatchDamageListener(engine), plugin);
        PlayerReservationService reservationService = container.getOptional(PlayerReservationService.class)
                .orElseGet(() -> ServiceLocatorImpl.getInstance() != null
                        ? ServiceLocatorImpl.getInstance().findService(PlayerReservationService.class).orElse(null)
                        : null);
        MessageBus messageBus = container.getOptional(MessageBus.class)
                .orElseGet(() -> ServiceLocatorImpl.getInstance() != null
                        ? ServiceLocatorImpl.getInstance().findService(MessageBus.class).orElse(null)
                        : null);

        routingListener = new PlayerRoutingListener(plugin, routeRegistry, gameManager, reservationService, messageBus, sessionService);
        Bukkit.getPluginManager().registerEvents(routingListener, plugin);
        if (ServiceLocatorImpl.getInstance() != null) {
            ServiceLocatorImpl.getInstance().registerService(PlayerRoutingListener.class, routingListener);
        }
        CommandRegistrar.register(new StateMachineCommand(engine).build());
        plugin.getLogger().info("MinigameEngine initialized");
    }

    @Override
    public void shutdown() {
        if (engine != null) {
            engine.shutdown();
        }
        if (routingListener != null) {
            routingListener.shutdown();
            if (ServiceLocatorImpl.getInstance() != null) {
                ServiceLocatorImpl.getInstance().unregisterService(PlayerRoutingListener.class);
            }
            routingListener = null;
        }
        if (ServiceLocatorImpl.getInstance() != null) {
            if (dataRegistry != null) {
                ServiceLocatorImpl.getInstance().unregisterService(MinigameDataRegistry.class);
            }
            if (matchHistoryWriter != null) {
                ServiceLocatorImpl.getInstance().unregisterService(MatchHistoryWriter.class);
            }
            if (matchLogWriter != null) {
                ServiceLocatorImpl.getInstance().unregisterService(MatchLogWriter.class);
            }
        }
        if (containerRef != null) {
            if (dataRegistry != null) {
                containerRef.unregister(MinigameDataRegistry.class);
            }
            if (matchHistoryWriter != null) {
                containerRef.unregister(MatchHistoryWriter.class);
            }
            if (matchLogWriter != null) {
                containerRef.unregister(MatchLogWriter.class);
            }
        }
        routeRegistry = null;
        environmentService = null;
        dataRegistry = null;
        matchHistoryWriter = null;
        matchLogWriter = null;
        containerRef = null;
    }

    private MinigameEnvironmentService createEnvironmentService(JavaPlugin plugin, DependencyContainer container) {
        WorldService worldService = container.getOptional(WorldService.class)
                .orElseGet(() -> ServiceLocatorImpl.getInstance() != null
                        ? ServiceLocatorImpl.getInstance().findService(WorldService.class).orElse(null)
                        : null);
        WorldManager worldManager = container.getOptional(WorldManager.class)
                .orElseGet(() -> ServiceLocatorImpl.getInstance() != null
                        ? ServiceLocatorImpl.getInstance().findService(WorldManager.class).orElse(null)
                        : null);
        if (worldService == null || worldManager == null) {
            plugin.getLogger().warning("Minigame environment service unavailable (world services missing)");
            return null;
        }
        Logger logger = plugin.getLogger();
        return new MinigameEnvironmentService(logger, worldService, worldManager);
    }
}
