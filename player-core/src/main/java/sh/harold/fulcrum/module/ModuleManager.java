package sh.harold.fulcrum.module;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.FulcrumModule;
import sh.harold.fulcrum.FulcrumPlatform;

import java.lang.annotation.Annotation;
import java.util.*;
import java.util.logging.Logger;

/**
 * Manages runtime loading and lifecycle of external Fulcrum modules.
 */
public class ModuleManager {
    private final List<ModuleMetadata> loadedModules = new ArrayList<>();
    private final Logger logger;

    public ModuleManager(Logger logger) {
        this.logger = logger;
    }

    public void loadAll(JavaPlugin corePlugin, FulcrumPlatform platform) {
        List<ModuleMetadata> discovered = new ArrayList<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin == corePlugin) continue;
            if (!(plugin instanceof JavaPlugin javaPlugin)) continue;
            var meta = javaPlugin.getPluginMeta(); // Paper API
            if (!meta.getPluginDependencies().contains(corePlugin.getName())) {
                logger.fine("[Module] Skipping " + plugin.getName() + ": no Fulcrum dependency");
                continue;
            }
            if (!(plugin instanceof FulcrumModule moduleInstance)) {
                logger.warning("[Module] Skipping " + plugin.getName() + ": does not implement FulcrumModule");
                continue;
            }
            ModuleInfo info = plugin.getClass().getAnnotation(ModuleInfo.class);
            if (info == null) {
                logger.warning("[Module] Skipping " + plugin.getName() + ": missing @ModuleInfo annotation");
                continue;
            }
            List<String> dependsOn = Arrays.asList(info.dependsOn());
            ModuleMetadata metadata = new ModuleMetadata(
                info.name(),
                dependsOn,
                info.description(),
                javaPlugin,
                moduleInstance
            );
            discovered.add(metadata);
        }
        List<ModuleMetadata> sorted;
        try {
            sorted = DependencyResolver.resolve(
                discovered,
                ModuleMetadata::name,
                ModuleMetadata::dependsOn
            );
        } catch (Exception e) {
            logger.severe(e.getMessage());
            return;
        }
        for (ModuleMetadata module : sorted) {
            try {
                module.instance().onEnable(platform);
                logger.info("[Module] Enabled: " + module.name());
                loadedModules.add(module);
            } catch (Exception e) {
                logger.severe("[Module] Failed to enable: " + module.name() + ": " + e.getMessage());
            }
        }
    }

    public void disableAll() {
        ListIterator<ModuleMetadata> it = loadedModules.listIterator(loadedModules.size());
        while (it.hasPrevious()) {
            ModuleMetadata module = it.previous();
            try {
                module.instance().onDisable();
                logger.info("[Module] Disabled: " + module.name());
            } catch (Exception e) {
                logger.severe("[Module] Failed to disable: " + module.name() + ": " + e.getMessage());
            }
        }
        loadedModules.clear();
    }

    public List<ModuleMetadata> getLoadedModules() {
        return Collections.unmodifiableList(loadedModules);
    }
}
