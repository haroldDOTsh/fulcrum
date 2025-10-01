package sh.harold.fulcrum.lifecycle;

import sh.harold.fulcrum.api.module.ServiceLocator;

import java.util.Optional;

/**
 * Implementation of ServiceLocator that wraps the DependencyContainer.
 * This provides the service location functionality while maintaining
 * separation between API and runtime modules.
 */
public class ServiceLocatorImpl implements ServiceLocator {
    private final DependencyContainer container;
    private static ServiceLocatorImpl instance;

    /**
     * Creates a new ServiceLocator implementation with the given container.
     *
     * @param container The dependency container to wrap
     */
    public ServiceLocatorImpl(DependencyContainer container) {
        if (container == null) {
            throw new IllegalArgumentException("DependencyContainer cannot be null");
        }
        this.container = container;
        instance = this;
    }

    /**
     * Get the singleton instance of ServiceLocatorImpl.
     * Note: This is only available after initialization.
     *
     * @return The instance, or null if not yet initialized
     */
    public static ServiceLocatorImpl getInstance() {
        return instance;
    }

    @Override
    public <T> Optional<T> findService(Class<T> serviceClass) {
        return container.getOptional(serviceClass);
    }

    /**
     * Register a service with the container.
     * This method is only available in the implementation.
     *
     * @param <T>          The type of the service
     * @param serviceClass The class of the service
     * @param instance     The service instance
     */
    public <T> void registerService(Class<T> serviceClass, T instance) {
        container.register(serviceClass, instance);
    }

    /**
     * Unregister a service from the container.
     * This method is only available in the implementation.
     *
     * @param <T>          The type of the service
     * @param serviceClass The class of the service
     */
    public <T> void unregisterService(Class<T> serviceClass) {
        container.unregister(serviceClass);
    }

    public static void reset() {
        instance = null;
    }
}
