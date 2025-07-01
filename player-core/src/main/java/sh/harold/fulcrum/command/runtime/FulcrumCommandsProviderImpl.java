package sh.harold.fulcrum.command.runtime;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.command.CommandExecutor;
import sh.harold.fulcrum.command.FulcrumCommandsProvider;

import java.util.List;
import java.util.logging.Level;

/**
 * Runtime implementation of FulcrumCommandsProvider.
 */
public final class FulcrumCommandsProviderImpl implements FulcrumCommandsProvider {
    @Override
    public void register(JavaPlugin plugin) {
        var logger = plugin.getLogger();
        logger.info("[FulcrumCommands] Starting command registration");
        var basePackage = plugin.getClass().getPackageName();
        var classLoader = plugin.getClass().getClassLoader();
        logger.info("[FulcrumCommands] Scanning package: " + basePackage);
        var commandClasses = CommandScanner.findCommandClasses(classLoader, basePackage);
        logger.info("[FulcrumCommands] Found command classes: " + commandClasses.size());
        var executors = new java.util.ArrayList<CommandExecutor>();
        for (var clazz : commandClasses) {
            try {
                logger.info("[FulcrumCommands] Instantiating command class: " + clazz.getName());
                var instance = (CommandExecutor) clazz.getDeclaredConstructor().newInstance();
                executors.add(instance);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to instantiate command class: " + clazz.getName(), e);
            }
        }
        logger.info("[FulcrumCommands] Instantiated executors: " + executors.size());
        if (!executors.isEmpty()) {
            logger.info("[FulcrumCommands] Registering commands with CommandRegistrationBridge");
            CommandRegistrationBridge.registerCommands(plugin, List.copyOf(executors));
        } else {
            logger.warning("[FulcrumCommands] No command executors found to register.");
        }
        logger.info("[FulcrumCommands] Command registration complete");
    }
}
