package sh.harold.fulcrum.module;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.module.commands.ModuleListCommand;

/**
 * Registers module-related commands and logic as a Fulcrum feature.
 *
 * This matches the pattern used by GamemodeFeature and other PluginFeature implementations.
 */
public class ModuleFeature implements PluginFeature {
    @Override
    public void initialize(JavaPlugin plugin) {
        var moduleManager = ((sh.harold.fulcrum.FulcrumPlugin) plugin).getModuleManager();
        CommandRegistrar.register(new ModuleListCommand(moduleManager).build());
        plugin.getLogger().info("Registered Module List Command");
    }

    @Override
    public void shutdown() {
        // No shutdown logic for now
    }
}
