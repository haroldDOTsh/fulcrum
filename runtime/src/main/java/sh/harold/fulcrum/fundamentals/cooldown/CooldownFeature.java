package sh.harold.fulcrum.fundamentals.cooldown;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.common.cooldown.CooldownRegistry;
import sh.harold.fulcrum.common.cooldown.InMemoryCooldownRegistry;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;

import java.util.logging.Logger;

/**
 * Boots the shared cooldown registry for the Paper runtime.
 */
public final class CooldownFeature implements PluginFeature {
    private InMemoryCooldownRegistry registry;
    private Logger logger;
    private DependencyContainer container;

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.logger = plugin.getLogger();
        this.container = container;
        if (registry != null) {
            logger.warning("Cooldown registry already initialised");
            return;
        }
        this.registry = new InMemoryCooldownRegistry();
        container.register(CooldownRegistry.class, registry);
        ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
        if (locator != null) {
            locator.registerService(CooldownRegistry.class, registry);
        }
        logger.info("Shared cooldown registry initialised");
    }

    @Override
    public void shutdown() {
        if (registry != null) {
            registry.close();
            registry = null;
        }
        if (container != null) {
            container.unregister(CooldownRegistry.class);
        }
        ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
        if (locator != null) {
            locator.unregisterService(CooldownRegistry.class);
        }
    }

    @Override
    public int getPriority() {
        return 40;
    }
}
