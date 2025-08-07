package sh.harold.fulcrum.api.module;

import java.util.Optional;

/**
 * Interface for locating and retrieving services from the platform.
 * This abstraction allows external modules to access platform services
 * without depending on internal implementation details.
 *
 * <p>Usage examples:</p>
 * <pre>
 * // Get service or null:
 * MyService service = locator.findService(MyService.class).orElse(null);
 *
 * // Check if service exists:
 * boolean exists = locator.findService(MyService.class).isPresent();
 *
 * // Get service with custom exception:
 * MyService service = locator.findService(MyService.class)
 *     .orElseThrow(() -> new IllegalStateException("Service required"));
 * </pre>
 */
public interface ServiceLocator {
    
    /**
     * Find a service instance wrapped in an Optional.
     * This is the primary way for modules to access core functionality.
     *
     * @param <T>          The type of the service
     * @param serviceClass The class of the service to retrieve
     * @return Optional containing the service, or empty if not available
     */
    <T> Optional<T> findService(Class<T> serviceClass);
}