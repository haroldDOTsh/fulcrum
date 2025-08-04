package sh.harold.fulcrum.environment;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import sh.harold.fulcrum.api.module.BootstrapContextHolder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Registry for managing module enablement decisions based on environment configuration.
 * Provides caching and plugin name resolution for performance.
 *
 * @since 1.2.0
 */
public class ModuleEnvironmentRegistry {
    private static final Logger LOGGER = Logger.getLogger(ModuleEnvironmentRegistry.class.getName());
    
    private final EnvironmentConfig config;
    private final String currentEnvironment;
    private final Map<String, Boolean> enablementCache;
    
    public ModuleEnvironmentRegistry(EnvironmentConfig config, String currentEnvironment) {
        this.config = config;
        this.currentEnvironment = currentEnvironment;
        this.enablementCache = new ConcurrentHashMap<>();
    }
    
    /**
     * Determines if a module is enabled based on the calling plugin context.
     * During bootstrap phase, uses BootstrapContextHolder for safe detection.
     * After bootstrap, uses stack trace analysis to identify the calling plugin.
     *
     * @return true if the calling module is enabled in the current environment
     * @throws IllegalStateException if the calling plugin cannot be determined
     */
    public boolean isThisModuleEnabled() {
        // Check if we're in bootstrap phase first
        if (BootstrapContextHolder.isInBootstrapPhase()) {
            String moduleId = BootstrapContextHolder.getCurrentModuleId();
            if (moduleId != null) {
                LOGGER.fine("Bootstrap phase detection for module: " + moduleId);
                return isModuleEnabled(moduleId);
            }
        }
        
        // Fallback to runtime detection (requires Bukkit APIs)
        if (!isBukkitAvailable()) {
            LOGGER.warning("Bukkit APIs not available and no bootstrap context found. Defaulting to enabled.");
            return true;
        }
        
        String pluginName = detectCallingPlugin();
        
        if (pluginName == null) {
            LOGGER.warning("Unable to detect calling plugin from stack trace, defaulting to enabled");
            return true; // Default to enabled if detection fails
        }
        
        return isModuleEnabled(pluginName);
    }
    
    /**
     * Checks if a specific module is enabled in the current environment.
     * Results are cached for performance.
     * 
     * @param pluginName the plugin/module name to check
     * @return true if the module is enabled
     */
    public boolean isModuleEnabled(String pluginName) {
        return enablementCache.computeIfAbsent(pluginName, name -> {
            boolean enabled = config.isModuleEnabled(name, currentEnvironment);
            LOGGER.fine("Module '" + name + "' enablement in environment '" + currentEnvironment + "': " + enabled);
            return enabled;
        });
    }
    
    /**
     * Detects the calling plugin by analyzing the stack trace.
     * Looks for plugin classes in the call stack and resolves them to plugin names.
     * 
     * @return the name of the calling plugin, or null if not found
     */
    private String detectCallingPlugin() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        
        // Start from index 3 to skip:
        // 0: Thread.getStackTrace()
        // 1: detectCallingPlugin()
        // 2: isThisModuleEnabled() or isModuleEnabled()
        for (int i = 3; i < stackTrace.length; i++) {
            StackTraceElement element = stackTrace[i];
            String className = element.getClassName();
            
            try {
                Class<?> clazz = Class.forName(className);
                Plugin plugin = findPluginForClass(clazz);
                
                if (plugin != null) {
                    String pluginName = plugin.getName();
                    LOGGER.fine("Detected calling plugin: " + pluginName + " from class: " + className);
                    return pluginName;
                }
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                // Continue searching if class cannot be loaded
                continue;
            }
        }
        
        LOGGER.warning("Could not determine calling plugin from stack trace");
        return null;
    }
    
    /**
     * Finds the plugin that owns a specific class.
     *
     * @param clazz the class to search for
     * @return the plugin that owns the class, or null if not found
     */
    private Plugin findPluginForClass(Class<?> clazz) {
        // Check if Bukkit is available before trying to use it
        if (!isBukkitAvailable()) {
            return null;
        }
        
        ClassLoader classLoader = clazz.getClassLoader();
        
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin.getClass().getClassLoader() == classLoader) {
                return plugin;
            }
        }
        
        return null;
    }
    
    /**
     * Checks if Bukkit APIs are available (i.e., not in bootstrap phase).
     *
     * @return true if Bukkit APIs can be used
     */
    private boolean isBukkitAvailable() {
        try {
            // Check if the plugin manager is available
            return Bukkit.getPluginManager() != null;
        } catch (Exception | Error e) {
            // Any exception means Bukkit is not ready
            return false;
        }
    }
    
    /**
     * Clears the enablement cache. Useful for testing or configuration reloading.
     */
    public void clearCache() {
        enablementCache.clear();
        LOGGER.info("Module enablement cache cleared");
    }
    
    /**
     * Gets the current environment name
     * @return the current environment
     */
    public String getCurrentEnvironment() {
        return currentEnvironment;
    }
    
    /**
     * Gets the current configuration
     * @return the environment configuration
     */
    public EnvironmentConfig getConfig() {
        return config;
    }
}