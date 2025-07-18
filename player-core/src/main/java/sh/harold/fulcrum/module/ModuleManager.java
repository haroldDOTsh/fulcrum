package sh.harold.fulcrum.module;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.FulcrumModule;
import sh.harold.fulcrum.FulcrumPlatform;

import java.util.*;
import java.util.logging.Logger;

/**
 * Manages runtime loading and lifecycle of external Fulcrum modules.
 */
public class ModuleManager {
    private final Logger logger;
    private List<ModuleMetadata> loadedModules = new ArrayList<>();

    public ModuleManager(Logger logger) {
        this.logger = logger;
    }

    /**
     * Loads and enables only the modules listed in allowedModules, respecting dependencies.
     */
    public void loadModules(List<String> allowedModules, FulcrumPlatform platform) {
        Map<String, ModuleMetadata> discovered = new HashMap<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (!(plugin instanceof FulcrumModule module)) continue;
            ModuleInfo info = plugin.getClass().getAnnotation(ModuleInfo.class);
            if (info == null) continue;
            if (!allowedModules.contains(info.name())) {
                plugin.getLogger().info("[Fulcrum] Skipping module: " + info.name() + " (not in role config)");
                continue;
            }
            discovered.put(info.name(), new ModuleMetadata(
                    info.name(),
                    List.of(info.dependsOn()),
                    info.description(),
                    (JavaPlugin) plugin,
                    module
            ));
        }

        // Strict validation: ensure every required module is present and valid
        List<String> missing = new ArrayList<>();
        for (String required : allowedModules) {
            if (!discovered.containsKey(required)) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            org.bukkit.Bukkit.getLogger().severe("[Fulcrum] The following required modules are missing or invalid:");
            for (String name : missing) {
                org.bukkit.Bukkit.getLogger().severe(" - " + name);
            }
            org.bukkit.Bukkit.getLogger().severe("[Fulcrum] Server is shutting down due to incomplete runtime configuration.");
            org.bukkit.Bukkit.shutdown();
            return;
        }

        List<ModuleMetadata> sorted;
        try {
            sorted = DependencyResolver.resolve(
                    new ArrayList<>(discovered.values()),
                    ModuleMetadata::name,
                    ModuleMetadata::dependsOn
            );
        } catch (Exception e) {
            logger.severe(e.getMessage());
            return;
        }
        loadedModules = new ArrayList<>();
        for (ModuleMetadata meta : sorted) {
            try {
                meta.instance().onEnable(platform);
                logger.info("[Fulcrum] Enabled module: " + meta.name());
                loadedModules.add(meta);
            } catch (Exception e) {
                logger.severe("[Fulcrum] Failed to enable module: " + meta.name());
                e.printStackTrace();
            }
        }
    }

    public void disableAll() {
        for (int i = loadedModules.size() - 1; i >= 0; i--) {
            ModuleMetadata meta = loadedModules.get(i);
            try {
                meta.instance().onDisable();
                logger.info("[Fulcrum] Disabled module: " + meta.name());
            } catch (Exception e) {
                logger.severe("Error disabling module: " + meta.name());
                e.printStackTrace();
            }
        }
        loadedModules.clear();
    }

    public List<ModuleMetadata> getLoadedModules() {
        return Collections.unmodifiableList(loadedModules);
    }
}
