package sh.harold.fulcrum.fundamentals.routing;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.lifecycle.ServerIdentifier;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.fundamentals.lifecycle.ServerLifecycleFeature;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;
import sh.harold.fulcrum.minigame.routing.PlayerRouteRegistry;

/**
 * Wires the environment routing service so other modules can request transfers.
 */
public final class EnvironmentRoutingFeature implements PluginFeature {
    private EnvironmentRoutingService service;

    @Override
    public int getPriority() {
        // Load after the minigame engine so the PlayerRouteRegistry is available.
        return 260;
    }

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        MessageBus messageBus = container.getOptional(MessageBus.class).orElse(null);
        PlayerRouteRegistry routeRegistry = container.getOptional(PlayerRouteRegistry.class).orElse(null);
        ServerLifecycleFeature lifecycleFeature = container.getOptional(ServerLifecycleFeature.class).orElse(null);
        ServerIdentifier serverIdentifier = container.getOptional(ServerIdentifier.class).orElse(null);

        if (messageBus == null || routeRegistry == null || lifecycleFeature == null || serverIdentifier == null) {
            plugin.getLogger().warning("EnvironmentRoutingFeature unavailable - required services missing.");
            return;
        }

        service = new EnvironmentRoutingService(plugin, messageBus, routeRegistry, lifecycleFeature, serverIdentifier);
        container.register(EnvironmentRoutingService.class, service);
        if (ServiceLocatorImpl.getInstance() != null) {
            ServiceLocatorImpl.getInstance().registerService(EnvironmentRoutingService.class, service);
        }
    }

    @Override
    public void shutdown() {
        if (ServiceLocatorImpl.getInstance() != null) {
            ServiceLocatorImpl.getInstance().unregisterService(EnvironmentRoutingService.class);
        }
        service = null;
    }
}

