package sh.harold.fulcrum;

import sh.harold.fulcrum.lifecycle.DependencyContainer;

import java.util.Optional;

/**
 * Gateway for exposing Fulcrum platform services to modules.
 * This acts as a stable API bridge, hiding internal implementation details
 * and providing a clean, safe interface for external modules.
 */
public class FulcrumPlatform {
    private final DependencyContainer container;

    /**
     * Creates a new FulcrumPlatform with access to the dependency container.
     *
     * @param container The dependency container holding core services
     */
    public FulcrumPlatform(DependencyContainer container) {
        if (container == null) {
            throw new IllegalArgumentException("DependencyContainer cannot be null");
        }
        this.container = container;
    }

    /**
     * Get a core service by its class type.
     * This is the primary way for modules to access core functionality.
     *
     * @param <T>          The type of the service
     * @param serviceClass The class of the service to retrieve
     * @return The service instance, or null if not available
     */
    public <T> T getService(Class<T> serviceClass) {
        return container.getOptional(serviceClass).orElse(null);
    }

    /**
     * Get a core service wrapped in an Optional.
     * This is useful when you want to handle the absence of a service gracefully.
     *
     * @param <T>          The type of the service
     * @param serviceClass The class of the service to retrieve
     * @return Optional containing the service, or empty if not available
     */
    public <T> Optional<T> getOptionalService(Class<T> serviceClass) {
        return container.getOptional(serviceClass);
    }

    /**
     * Check if a service is available.
     *
     * @param serviceClass The class of the service to check
     * @return true if the service is available, false otherwise
     */
    public boolean hasService(Class<?> serviceClass) {
        return container.isAvailable(serviceClass);
    }
}
