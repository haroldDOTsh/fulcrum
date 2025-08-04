package sh.harold.fulcrum.api.module;

import java.util.Map;
import java.util.Set;

/**
 * Static utility for environment detection and module enablement checks.
 * This class is initialized by Fulcrum's bootstrapper and provides
 * context-aware environment checking capabilities to module bootstrappers.
 *
 * @since 1.2.0
 */
public final class FulcrumEnvironment {
    private static String currentEnvironment = null;
    private static Map<String, Set<String>> environmentConfig = null;
    private static boolean initialized = false;
    
    // Legacy support
    private static Object moduleRegistry = null;
    
    private FulcrumEnvironment() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    /**
     * Initializes the environment with configuration support. Called only by FulcrumBootstrapper.
     * This is the new preferred initialization method for bootstrap-safe module detection.
     *
     * @param environment The detected environment name
     * @param config The environment configuration mapping environment names to module sets
     * @throws IllegalStateException if already initialized
     * @since 1.3.0
     */
    public static void initialize(String environment, Map<String, Set<String>> config) {
        if (initialized) {
            throw new IllegalStateException("FulcrumEnvironment already initialized");
        }
        currentEnvironment = environment;
        environmentConfig = config;
        initialized = true;
    }
    
    /**
     * Legacy initialization with registry support for backward compatibility.
     * @param environment The detected environment name
     * @param registry The module environment registry for enablement checks
     * @throws IllegalStateException if already initialized
     * @deprecated Use initialize(String, Map) instead
     */
    @Deprecated
    public static void initialize(String environment, Object registry) {
        if (initialized) {
            throw new IllegalStateException("FulcrumEnvironment already initialized");
        }
        currentEnvironment = environment;
        moduleRegistry = registry;
        initialized = true;
    }
    
    /**
     * Legacy initialization method for backwards compatibility.
     * @param environment The detected environment name
     * @throws IllegalStateException if already initialized
     * @deprecated Use initialize(String, Map) instead
     */
    @Deprecated
    public static void initialize(String environment) {
        initialize(environment, (Object)null);
    }
    
    /**
     * Check if the calling module is enabled in the current environment.
     * During bootstrap phase, uses BootstrapContextHolder to identify the module.
     * Outside bootstrap phase, falls back to legacy stack trace detection.
     *
     * @return true if the module is enabled
     * @throws IllegalStateException if not initialized or if context detection fails
     */
    public static boolean isThisModuleEnabled() {
        if (!initialized) {
            throw new IllegalStateException("FulcrumEnvironment not initialized. Ensure Fulcrum is loaded before this module.");
        }
        
        // Try bootstrap-safe detection first
        if (BootstrapContextHolder.isInBootstrapPhase()) {
            String moduleId = BootstrapContextHolder.getCurrentModuleId();
            if (moduleId == null) {
                throw new IllegalStateException(
                    "Module ID not found in context. Ensure your bootstrap class is annotated with @ModuleID " +
                    "and BootstrapContextHolder.setContext() is called"
                );
            }
            
            // Use the new configuration-based check if available
            if (environmentConfig != null) {
                return isModuleEnabledInEnvironment(moduleId);
            }
        }
        
        // Fallback to legacy registry-based detection
        if (moduleRegistry == null) {
            // If no registry and no config, default to enabled
            return true;
        }
        
        try {
            // Use reflection to call the registry method to avoid circular dependencies
            java.lang.reflect.Method method = moduleRegistry.getClass().getMethod("isThisModuleEnabled");
            return (Boolean) method.invoke(moduleRegistry);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to determine module enablement: " + e.getMessage(), e);
        }
    }
    
    /**
     * Checks if a specific module is enabled in the current environment.
     * This method uses the configuration-based approach and doesn't require Bukkit APIs.
     *
     * @param moduleId the module identifier to check
     * @return true if the module is enabled
     * @since 1.3.1
     */
    private static boolean isModuleEnabledInEnvironment(String moduleId) {
        if (environmentConfig == null) {
            // No configuration means all modules are enabled
            return true;
        }
        
        // Check global modules first
        Set<String> globalModules = environmentConfig.get("global");
        if (globalModules != null && globalModules.contains(moduleId)) {
            return true;
        }
        
        // Check environment-specific modules
        Set<String> envModules = environmentConfig.get(currentEnvironment);
        return envModules != null && envModules.contains(moduleId);
    }
    
    /**
     * Checks if the current environment matches any of the provided environments.
     * @param environments Variable arguments of environment names to check
     * @return true if current environment matches any provided environment
     * @throws IllegalStateException if not initialized
     * @deprecated Use isThisModuleEnabled() with environment.yml configuration instead
     */
    @Deprecated
    public static boolean isEnabledFor(String... environments) {
        if (!initialized) {
            throw new IllegalStateException("FulcrumEnvironment not initialized. Ensure Fulcrum is loaded before this module.");
        }
        
        if (environments == null || environments.length == 0) {
            return true; // No restrictions means enabled in all environments
        }
        
        for (String env : environments) {
            if (currentEnvironment.equalsIgnoreCase(env)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Gets the current environment name.
     * @return The current environment
     * @throws IllegalStateException if not initialized
     */
    public static String getCurrent() {
        if (!initialized) {
            throw new IllegalStateException("FulcrumEnvironment not initialized");
        }
        return currentEnvironment;
    }
}