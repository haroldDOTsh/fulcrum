package sh.harold.fulcrum.environment;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Data class for parsed environment configuration from environment.yml
 * 
 * @since 1.2.0
 */
public final class EnvironmentConfig {
    private final Map<String, Set<String>> environmentModules;
    
    public EnvironmentConfig(Map<String, Set<String>> environmentModules) {
        this.environmentModules = Map.copyOf(environmentModules);
    }
    
    /**
     * Gets all modules configured for a specific environment
     * @param environment the environment name
     * @return set of module names for the environment, empty if not found
     */
    public Set<String> getModulesForEnvironment(String environment) {
        return environmentModules.getOrDefault(environment, Collections.emptySet());
    }
    
    /**
     * Gets all global modules that are enabled in every environment
     * @return set of global module names
     */
    public Set<String> getGlobalModules() {
        return environmentModules.getOrDefault("global", Collections.emptySet());
    }
    
    /**
     * Checks if a module is enabled in the specified environment
     * @param moduleName the module name to check
     * @param environment the environment name
     * @return true if module is enabled (either globally or in specific environment)
     */
    public boolean isModuleEnabled(String moduleName, String environment) {
        // Check global modules first
        if (getGlobalModules().contains(moduleName)) {
            return true;
        }
        
        // Check environment-specific modules
        return getModulesForEnvironment(environment).contains(moduleName);
    }
    
    /**
     * Gets all configured environments (excluding 'global')
     * @return set of environment names
     */
    public Set<String> getEnvironments() {
        return environmentModules.keySet().stream()
                .filter(env -> !"global".equals(env))
                .collect(java.util.stream.Collectors.toSet());
    }
    
    /**
     * Gets all environment configuration mappings
     * @return immutable map of environment to module sets
     */
    public Map<String, Set<String>> getAllMappings() {
        return environmentModules;
    }
}