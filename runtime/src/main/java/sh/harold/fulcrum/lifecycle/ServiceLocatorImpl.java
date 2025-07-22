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
    }

    @Override
    public <T> T getService(Class<T> serviceClass) {
        return container.getOptional(serviceClass).orElse(null);
    }

    @Override
    public <T> Optional<T> findService(Class<T> serviceClass) {
        return container.getOptional(serviceClass);
    }

    @Override
    public boolean hasService(Class<?> serviceClass) {
        return container.isAvailable(serviceClass);
    }
}