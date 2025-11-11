package sh.harold.fulcrum.velocity.fundamentals.cooldown;

import org.slf4j.Logger;
import sh.harold.fulcrum.common.cooldown.CooldownRegistry;
import sh.harold.fulcrum.common.cooldown.InMemoryCooldownRegistry;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;

/**
 * Provides the shared cooldown registry inside the Velocity runtime.
 */
public final class VelocityCooldownFeature implements VelocityFeature {

    private InMemoryCooldownRegistry registry;
    private ServiceLocator serviceLocator;

    @Override
    public String getName() {
        return "cooldown-registry";
    }

    @Override
    public int getPriority() {
        return 40;
    }

    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) {
        this.registry = new InMemoryCooldownRegistry();
        this.serviceLocator = serviceLocator;
        serviceLocator.register(CooldownRegistry.class, registry);
        logger.info("Shared cooldown registry initialised");
    }

    @Override
    public void shutdown() {
        if (registry != null) {
            registry.close();
            registry = null;
        }
        if (serviceLocator != null) {
            serviceLocator.unregister(CooldownRegistry.class);
            serviceLocator = null;
        }
    }
}
