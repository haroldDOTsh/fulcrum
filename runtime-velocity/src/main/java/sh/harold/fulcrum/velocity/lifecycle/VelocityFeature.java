package sh.harold.fulcrum.velocity.lifecycle;

import org.slf4j.Logger;

public interface VelocityFeature {
    
    /**
     * Get the unique name of this feature
     * @return feature name
     */
    String getName();
    
    /**
     * Get the priority of this feature (lower = loads first)
     * @return priority value (default 100)
     */
    default int getPriority() {
        return 100;
    }
    
    /**
     * Initialize the feature
     * @param serviceLocator service locator for dependency injection
     * @param logger logger instance
     * @throws Exception if initialization fails
     */
    void initialize(ServiceLocator serviceLocator, Logger logger) throws Exception;
    
    /**
     * Shutdown the feature
     */
    void shutdown();
    
    /**
     * Check if the feature is enabled
     * @return true if enabled
     */
    default boolean isEnabled() {
        return true;
    }
}
