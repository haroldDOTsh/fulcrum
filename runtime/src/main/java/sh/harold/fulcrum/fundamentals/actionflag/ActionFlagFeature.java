package sh.harold.fulcrum.fundamentals.actionflag;

import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.fundamentals.actionflag.command.FlagDebugCommand;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;

import java.util.logging.Logger;

/**
 * Boots the action flag service and registers enforcement listeners.
 */
public final class ActionFlagFeature implements PluginFeature {
    private ActionFlagService service;
    private ActionFlagListener listener;
    private FlagDebugCommand command;
    private VanishService vanishService;
    private ActionFlagEffects flagEffects;

    @Override
    public int getPriority() {
        return 120;
    }

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        Logger logger = plugin.getLogger();
        this.service = new ActionFlagService(logger);
        container.register(ActionFlagService.class, service);
        if (ServiceLocatorImpl.getInstance() != null) {
            ServiceLocatorImpl.getInstance().registerService(ActionFlagService.class, service);
        }

        this.vanishService = new VanishService(plugin);
        container.register(VanishService.class, vanishService);
        if (ServiceLocatorImpl.getInstance() != null) {
            ServiceLocatorImpl.getInstance().registerService(VanishService.class, vanishService);
        }

        // Register built-in bundles
        service.registerContext(ActionFlagContexts.LOBBY_DEFAULT, ActionFlagPresets.lobbyDefault());
        service.registerContext(ActionFlagContexts.MATCH_PREGAME_DEFAULT, ActionFlagPresets.matchPregameDefault());
        service.registerContext(ActionFlagContexts.MATCH_ACTIVE_FALLBACK, ActionFlagPresets.matchActiveFallback());

        // Register event listeners
        listener = new ActionFlagListener(service);
        Bukkit.getPluginManager().registerEvents(listener, plugin);

        command = new FlagDebugCommand(service);
        command.register(plugin);

        flagEffects = new ActionFlagEffects(plugin, service, vanishService);

        logger.info("ActionFlagFeature initialized");
    }

    @Override
    public void shutdown() {
        if (flagEffects != null) {
            flagEffects.shutdown();
            flagEffects = null;
        }
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
        if (command != null) {
            command.unregister();
            command = null;
        }
        if (vanishService != null) {
            vanishService.shutdown();
            if (ServiceLocatorImpl.getInstance() != null) {
                ServiceLocatorImpl.getInstance().unregisterService(VanishService.class);
            }
            vanishService = null;
        }
        if (ServiceLocatorImpl.getInstance() != null) {
            ServiceLocatorImpl.getInstance().unregisterService(ActionFlagService.class);
        }
        service = null;
    }
}
