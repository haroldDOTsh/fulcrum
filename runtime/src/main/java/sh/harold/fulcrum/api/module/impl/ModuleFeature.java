package sh.harold.fulcrum.api.module.impl;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.api.module.impl.commands.ModuleListCommand;

/**
 * Registers module-related commands and logic as a Fulcrum feature.
 * <p>
 * This matches the pattern used by GamemodeFeature and other PluginFeature implementations.
 */
public class ModuleFeature implements PluginFeature {
    private boolean commandsRegistered = false;
    private JavaPlugin plugin;

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;

        // Check if ModuleManager is available
        container.getOptional(ModuleManager.class).ifPresent(moduleManager -> {
            registerCommands(moduleManager);
        });

        // If not available now, it might be registered later
        if (!commandsRegistered) {
            plugin.getLogger().info("ModuleFeature initialized (waiting for ModuleManager)");
        }
    }

    @Override
    public void initialize(JavaPlugin plugin) {
        // Legacy initialization - just store the plugin reference
        this.plugin = plugin;
        plugin.getLogger().info("ModuleFeature initialized (commands deferred)");
    }

    /**
     * Register module-related commands after ModuleManager is available.
     *
     * @param moduleManager The initialized ModuleManager instance
     */
    public void registerCommands(ModuleManager moduleManager) {
        if (commandsRegistered) {
            plugin.getLogger().warning("Module commands already registered!");
            return;
        }

        if (moduleManager == null) {
            plugin.getLogger().severe("Cannot register module commands: ModuleManager is null!");
            return;
        }

        try {
            CommandRegistrar.register(new ModuleListCommand(plugin, moduleManager).build());
            commandsRegistered = true;
            plugin.getLogger().info("Registered Module List Command");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to register module commands: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void shutdown() {
        // No shutdown logic for now
    }

    @Override
    public int getPriority() {
        // Module feature should initialize later, after core systems
        return 150;
    }

    @Override
    public Class<?>[] getDependencies() {
        // ModuleFeature depends on ModuleManager being available
        return new Class<?>[]{ModuleManager.class};
    }

    public boolean areCommandsRegistered() {
        return commandsRegistered;
    }
}
