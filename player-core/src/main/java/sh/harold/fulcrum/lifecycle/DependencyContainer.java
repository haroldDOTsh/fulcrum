package sh.harold.fulcrum.lifecycle;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A simple dependency injection container for managing plugin services.
 * Provides a centralized way to register and retrieve services, avoiding
 * initialization order issues.
 */
public class DependencyContainer {
    private final Map<Class<?>, Object> services = new HashMap<>();
    private final Map<Class<?>, ServiceProvider<?>> providers = new HashMap<>();

    /**
     * Register a service instance.
     *
     * @param serviceClass   The service interface or class
     * @param implementation The service implementation
     * @param <T>            The service type
     */
    public <T> void register(Class<T> serviceClass, T implementation) {
        if (implementation == null) {
            throw new IllegalArgumentException("Cannot register null implementation for " + serviceClass.getName());
        }
        services.put(serviceClass, implementation);
    }

    /**
     * Register a lazy service provider.
     *
     * @param serviceClass The service interface or class
     * @param provider     The service provider
     * @param <T>          The service type
     */
    public <T> void registerProvider(Class<T> serviceClass, ServiceProvider<T> provider) {
        if (provider == null) {
            throw new IllegalArgumentException("Cannot register null provider for " + serviceClass.getName());
        }
        providers.put(serviceClass, provider);
    }

    /**
     * Get a service instance.
     *
     * @param serviceClass The service class
     * @param <T>          The service type
     * @return The service instance
     * @throws IllegalStateException if service is not available
     */
    public <T> T get(Class<T> serviceClass) {
        Optional<T> service = getOptional(serviceClass);
        return service.orElseThrow(() ->
                new IllegalStateException("Service not available: " + serviceClass.getName()));
    }

    /**
     * Get a service instance if available.
     *
     * @param serviceClass The service class
     * @param <T>          The service type
     * @return Optional containing the service if available
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getOptional(Class<T> serviceClass) {
        // Check direct registrations first
        Object service = services.get(serviceClass);
        if (service != null) {
            return Optional.of((T) service);
        }

        // Check providers
        ServiceProvider<?> provider = providers.get(serviceClass);
        if (provider != null) {
            T instance = (T) provider.provide();
            if (instance != null) {
                // Cache the instance for future use
                services.put(serviceClass, instance);
                return Optional.of(instance);
            }
        }

        return Optional.empty();
    }

    /**
     * Check if a service is available.
     *
     * @param serviceClass The service class
     * @return true if the service is available
     */
    public boolean isAvailable(Class<?> serviceClass) {
        return services.containsKey(serviceClass) || providers.containsKey(serviceClass);
    }

    /**
     * Clear all registered services.
     */
    public void clear() {
        services.clear();
        providers.clear();
    }

    /**
     * Functional interface for lazy service providers.
     *
     * @param <T> The service type
     */
    @FunctionalInterface
    public interface ServiceProvider<T> {
        T provide();
    }
}