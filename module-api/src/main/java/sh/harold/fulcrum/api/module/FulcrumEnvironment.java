package sh.harold.fulcrum.api.module;

/**
 * Static utility for environment detection and module enablement checks.
 * This class is initialized by Fulcrum's bootstrapper and provides
 * context-aware environment checking capabilities to module bootstrappers.
 *
 * @since 1.2.0
 */
public final class FulcrumEnvironment {
    private static String currentEnvironment = null;
    private static boolean initialized = false;
    private static Object moduleRegistry = null; // Will be set to ModuleEnvironmentRegistry
    
    private FulcrumEnvironment() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    /**
     * Initializes the environment with configuration support. Called only by FulcrumBootstrapper.
     * @param environment The detected environment name
     * @param registry The module environment registry for enablement checks
     * @throws IllegalStateException if already initialized
     */
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
     * @deprecated Use initialize(String, Object) instead
     */
    @Deprecated
    public static void initialize(String environment) {
        initialize(environment, null);
    }
    
    /**
     * Check if the calling module is enabled in the current environment.
     * Uses stack trace detection to automatically identify the calling plugin.
     *
     * @return true if the module is enabled
     * @throws IllegalStateException if not initialized or if context detection fails
     */
    public static boolean isThisModuleEnabled() {
        if (!initialized) {
            throw new IllegalStateException("FulcrumEnvironment not initialized. Ensure Fulcrum is loaded before this module.");
        }
        
        if (moduleRegistry == null) {
            // Fallback to legacy behavior - assume enabled
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