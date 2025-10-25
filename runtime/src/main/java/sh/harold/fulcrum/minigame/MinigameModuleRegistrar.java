package sh.harold.fulcrum.minigame;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.module.FulcrumModule;
import sh.harold.fulcrum.api.module.impl.ModuleManager;
import sh.harold.fulcrum.api.module.impl.ModuleMetadata;
import sh.harold.fulcrum.fundamentals.slot.discovery.SlotFamilyService;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Coordinates minigame registration once dependent Fulcrum modules become available.
 * Hooks PluginEnableEvent so that late-loading modules are picked up without per-module workarounds.
 */
public final class MinigameModuleRegistrar implements Listener {
    private final JavaPlugin plugin;
    private final ModuleManager moduleManager;
    private final SlotFamilyService slotFamilyService;
    private final MinigameEngine engine;
    private final Logger logger;
    private final Set<String> registeredModules = ConcurrentHashMap.newKeySet();
    private boolean initialized = false;

    public MinigameModuleRegistrar(JavaPlugin plugin,
                                   ModuleManager moduleManager,
                                   SlotFamilyService slotFamilyService,
                                   MinigameEngine engine) {
        this.plugin = plugin;
        this.moduleManager = moduleManager;
        this.slotFamilyService = slotFamilyService;
        this.engine = engine;
        this.logger = plugin.getLogger();
    }

    public void initialize() {
        if (initialized) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        scanAndRegisterModules();
        initialized = true;
    }

    public void shutdown() {
        HandlerList.unregisterAll(this);
        registeredModules.clear();
        initialized = false;
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        Plugin enabledPlugin = event.getPlugin();
        if (!(enabledPlugin instanceof FulcrumModule)) {
            return;
        }
        // Ensure we process after Bukkit marks the plugin as enabled
        Bukkit.getScheduler().runTask(plugin, this::scanAndRegisterModules);
    }

    private void scanAndRegisterModules() {
        boolean discovered = false;
        for (ModuleMetadata metadata : moduleManager.getLoadedModules()) {
            if (!registeredModules.add(metadata.name())) {
                continue;
            }
            if (!(metadata.instance() instanceof MinigameModule minigameModule)) {
                continue;
            }
            for (MinigameRegistration registration : minigameModule.registerMinigames(engine)) {
                engine.registerRegistration(registration);
                discovered = true;
                logger.info(() -> "Registered minigame family " + registration.getFamilyId()
                        + " from module " + metadata.name());
            }
        }
        if (discovered && slotFamilyService != null) {
            slotFamilyService.refreshDescriptors();
        }
    }
}
