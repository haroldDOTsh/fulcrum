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

    private FulcrumEnvironment() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Initializes the environment with configuration support. Called only by FulcrumBootstrapper.
     * This is the preferred initialization method for bootstrap-safe module detection.
     *
     * @param environment The detected environment name
     * @param config      The environment configuration mapping environment names to module sets
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
     * Check if the calling module is enabled in the current environment.
     * During bootstrap phase, uses BootstrapContextHolder to identify the module.
     *
     * @return true if the module is enabled
     * @throws IllegalStateException if not initialized or if context detection fails
     */
    public static boolean isThisModuleEnabled() {
        if (!initialized) {
            throw new IllegalStateException("FulcrumEnvironment not initialized. Ensure Fulcrum is loaded before this module.");
        }

        // Try bootstrap-safe detection
        if (BootstrapContextHolder.isInBootstrapPhase()) {
            String moduleId = BootstrapContextHolder.getCurrentModuleId();
            if (moduleId == null) {
                throw new IllegalStateException(
                        "Module ID not found in context. Ensure BootstrapContextHolder.setContext() is called " +
                                "with your module ID before checking enablement"
                );
            }

            // Use the configuration-based check
            if (environmentConfig != null) {
                return isModuleEnabledInEnvironment(moduleId);
            }
        }

        // If no config, default to enabled
        return true;
    }

    /**
     * Checks if a specific module is enabled in the current environment.
     * This method uses the configuration-based approach.
     *
     * @param moduleId the module identifier to check
     * @return true if the module is enabled
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
     * Gets the current environment name.
     *
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