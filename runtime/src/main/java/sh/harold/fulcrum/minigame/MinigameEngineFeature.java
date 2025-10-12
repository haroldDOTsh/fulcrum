package sh.harold.fulcrum.minigame;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.fundamentals.slot.SimpleSlotOrchestrator;
import sh.harold.fulcrum.fundamentals.world.WorldManager;
import sh.harold.fulcrum.fundamentals.world.WorldService;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;
import sh.harold.fulcrum.minigame.environment.MinigameEnvironmentService;
import sh.harold.fulcrum.minigame.listener.PlayerRoutingListener;
import sh.harold.fulcrum.minigame.listener.SpectatorListener;
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

    @Override
    public int getPriority() {
        return 220; // Runs after world feature so environment services are available
    }

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
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
        engine = new MinigameEngine(plugin, routeRegistry, environmentService, orchestrator);
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
        routingListener = new PlayerRoutingListener(plugin, routeRegistry, gameManager);
        Bukkit.getPluginManager().registerEvents(routingListener, plugin);
        if (ServiceLocatorImpl.getInstance() != null) {
            ServiceLocatorImpl.getInstance().registerService(PlayerRoutingListener.class, routingListener);
        }
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
        routeRegistry = null;
        environmentService = null;
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
