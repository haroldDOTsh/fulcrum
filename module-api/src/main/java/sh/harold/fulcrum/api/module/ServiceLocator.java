package sh.harold.fulcrum.api.module;

import java.util.Optional;

/**
 * Interface for locating and retrieving services from the platform.
 * This abstraction allows external modules to access platform services
 * without depending on internal implementation details.
 */
public interface ServiceLocator {
    
    /**
     * Get a service instance by its class type.
     * This is the primary way for modules to access core functionality.
     *
     * @param <T>          The type of the service
     * @param serviceClass The class of the service to retrieve
     * @return The service instance, or null if not available
     */
    <T> T getService(Class<T> serviceClass);
    
    /**
     * Find a service instance wrapped in an Optional.
     * This is useful when you want to handle the absence of a service gracefully.
     *
     * @param <T>          The type of the service
     * @param serviceClass The class of the service to retrieve
     * @return Optional containing the service, or empty if not available
     */
    <T> Optional<T> findService(Class<T> serviceClass);
    
    /**
     * Check if a service is available.
     *
     * @param serviceClass The class of the service to check
     * @return true if the service is available, false otherwise
     */
    boolean hasService(Class<?> serviceClass);
}