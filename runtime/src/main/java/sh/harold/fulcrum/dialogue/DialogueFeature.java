package sh.harold.fulcrum.dialogue;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.common.cooldown.CooldownRegistry;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;

import java.util.logging.Logger;

/**
 * Registers the dialogue service inside the runtime container.
 */
public final class DialogueFeature implements PluginFeature {

    private DialogueService service;
    private DependencyContainer container;

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.container = container;
        Logger logger = plugin.getLogger();
        CooldownRegistry registry = container.getOptional(CooldownRegistry.class)
                .orElseGet(() -> ServiceLocatorImpl.getInstance() != null
                        ? ServiceLocatorImpl.getInstance().findService(CooldownRegistry.class).orElse(null)
                        : null);
        if (registry == null) {
            throw new IllegalStateException("Cooldown registry unavailable; DialogueFeature requires CooldownFeature");
        }
        this.service = new DefaultDialogueService(registry, logger);
        container.register(DialogueService.class, service);
        ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
        if (locator != null) {
            locator.registerService(DialogueService.class, service);
        }
        logger.info("Dialogue service initialised");
    }

    @Override
    public void shutdown() {
        if (service != null && container != null) {
            container.unregister(DialogueService.class);
            ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
            if (locator != null) {
                locator.unregisterService(DialogueService.class);
            }
            service = null;
        }
    }

    @Override
    public int getPriority() {
        // after cooldown feature to ensure registry exists, before consumers.
        return 60;
    }
}
