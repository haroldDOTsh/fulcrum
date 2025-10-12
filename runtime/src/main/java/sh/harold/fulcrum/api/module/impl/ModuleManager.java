package sh.harold.fulcrum.api.module.impl;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.module.FulcrumModule;
import sh.harold.fulcrum.api.module.ModuleInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Provides discovery functionality for loaded Fulcrum modules.
 * Module loading is now handled by each module's bootstrap phase.
 *
 * @since 1.2.0
 */
public class ModuleManager {
    private final Logger logger;
    private final Plugin plugin;

    public ModuleManager(Logger logger, Plugin plugin) {
        this.logger = logger;
        this.plugin = plugin;
    }


    /**
     * Discovers and disables all loaded Fulcrum modules.
     * Since modules now self-manage via bootstrap, this provides graceful shutdown functionality.
     */
    public void disableAll() {
        List<ModuleMetadata> modules = getLoadedModules();

        // Disable in reverse order to respect dependencies
        for (int i = modules.size() - 1; i >= 0; i--) {
            ModuleMetadata meta = modules.get(i);
            try {
                meta.instance().onDisable();
                logger.info("[Fulcrum] Disabled module: " + meta.name());
            } catch (Exception e) {
                logger.severe("Error disabling module: " + meta.name());
                e.printStackTrace();
            }
        }
    }

    /**
     * Discovers and returns currently loaded Fulcrum modules.
     * Since modules now self-manage via bootstrap, this provides discovery-only functionality.
     */
    public List<ModuleMetadata> getLoadedModules() {
        List<ModuleMetadata> discoveredModules = new ArrayList<>();

        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (!(plugin instanceof FulcrumModule module)) continue;
            if (!plugin.isEnabled()) continue; // Only include enabled plugins

            ModuleInfo info = plugin.getClass().getAnnotation(ModuleInfo.class);
            if (info == null) continue;

            discoveredModules.add(new ModuleMetadata(
                    info.name(),
                    List.of(info.dependsOn()),
                    info.description(),
                    (JavaPlugin) plugin,
                    module
            ));
        }

        return discoveredModules;
    }
}
