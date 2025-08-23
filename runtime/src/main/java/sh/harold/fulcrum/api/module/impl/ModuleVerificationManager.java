package sh.harold.fulcrum.api.module.impl;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;
import sh.harold.fulcrum.api.module.FulcrumModule;
import sh.harold.fulcrum.environment.EnvironmentConfig;
import sh.harold.fulcrum.api.module.FulcrumEnvironment;

import java.util.*;
import java.util.logging.Logger;

/**
 * Manages module verification after server load to ensure all required modules are available.
 * This solves the timing issue where modules are checked before dependent plugins are loaded.
 */
public class ModuleVerificationManager implements Listener {
    private final Logger logger;
    private final EnvironmentConfig environmentConfig;
    private final Plugin plugin;
    private boolean verified = false;
    
    public ModuleVerificationManager(Logger logger, EnvironmentConfig environmentConfig, Plugin plugin) {
        this.logger = logger;
        this.environmentConfig = environmentConfig;
        this.plugin = plugin;
    }
    
    /**
     * Register this manager to listen for server load events
     */
    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        
        // Also schedule a fallback verification for servers without ServerLoadEvent
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!verified) {
                logger.info("Running fallback module verification...");
                verifyModules();
            }
        }, 40L); // 2 seconds after registration
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerLoad(ServerLoadEvent event) {
        try {
            if (event.getType() == ServerLoadEvent.LoadType.STARTUP) {
                logger.info("Server fully loaded, verifying modules...");
                verifyModules();
            }
        } catch (NoSuchMethodError | NoClassDefFoundError e) {
            // Older versions don't have LoadType
            logger.info("Server load detected, verifying modules...");
            verifyModules();
        }
    }
    
    /**
     * Verify that all configured modules are loaded
     */
    public void verifyModules() {
        if (verified) {
            return;
        }
        verified = true;
        
        String currentEnvironment = FulcrumEnvironment.getCurrent();
        Set<String> expectedModules = new HashSet<>();
        
        // Collect all modules that should be loaded
        expectedModules.addAll(environmentConfig.getGlobalModules());
        expectedModules.addAll(environmentConfig.getModulesForEnvironment(currentEnvironment));
        
        if (expectedModules.isEmpty()) {
            logger.info("No modules configured for environment '" + currentEnvironment + "'");
            return;
        }
        
        // Check which modules are actually loaded
        List<String> loadedModuleIds = new ArrayList<>();
        List<String> missingModules = new ArrayList<>();
        List<String> invalidModules = new ArrayList<>();
        
        for (String moduleId : expectedModules) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin(moduleId);
            
            if (plugin == null) {
                missingModules.add(moduleId);
            } else if (plugin instanceof FulcrumModule) {
                if (plugin.isEnabled()) {
                    loadedModuleIds.add(moduleId);
                } else {
                    missingModules.add(moduleId + " (disabled)");
                }
            } else {
                invalidModules.add(moduleId);
            }
        }
        
        // Log results
        if (!loadedModuleIds.isEmpty()) {
            logger.info("Successfully verified " + loadedModuleIds.size() + " module(s): " + 
                String.join(", ", loadedModuleIds));
        }
        
        if (!missingModules.isEmpty()) {
            logger.warning("Missing " + missingModules.size() + " module(s): " + 
                String.join(", ", missingModules));
            logger.warning("Ensure these modules are installed and have Fulcrum as a dependency in their plugin.yml");
        }
        
        if (!invalidModules.isEmpty()) {
            logger.warning("Found " + invalidModules.size() + " plugin(s) that are not Fulcrum modules: " + 
                String.join(", ", invalidModules));
            logger.warning("These plugins exist but don't implement FulcrumModule interface");
        }
    }
    
    /**
     * Get a list of all expected modules for the current environment
     */
    public Set<String> getExpectedModules() {
        String currentEnvironment = FulcrumEnvironment.getCurrent();
        Set<String> modules = new HashSet<>();
        modules.addAll(environmentConfig.getGlobalModules());
        modules.addAll(environmentConfig.getModulesForEnvironment(currentEnvironment));
        return modules;
    }
}