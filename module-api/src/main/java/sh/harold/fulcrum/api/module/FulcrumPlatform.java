package sh.harold.fulcrum.api.module;

import java.util.Optional;

/**
 * Platform interface provided to external modules.
 * 
 * This class exists to provide a stable API for external modules that depend
 * only on module-api. While it currently just delegates to ServiceLocator,
 * it serves as the contract between external modules and the Fulcrum runtime.
 * 
 * External modules access this via FulcrumPlatformHolder.getPlatform()
 * to interact with Fulcrum services.
 * 
 * @author Harold
 * @since 1.0.0
 */
public class FulcrumPlatform {
    private final ServiceLocator serviceLocator;

    public FulcrumPlatform(ServiceLocator serviceLocator) {
        this.serviceLocator = serviceLocator;
    }

    /**
     * Find a service by its class type.
     * 
     * @param <T> The service type
     * @param serviceClass The class of the service to find
     * @return An Optional containing the service if found, empty otherwise
     */
    public <T> Optional<T> findService(Class<T> serviceClass) {
        return serviceLocator.findService(serviceClass);
    }
}