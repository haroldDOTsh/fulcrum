package sh.harold.fulcrum.velocity.lifecycle;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class ServiceLocator {
    
    private final Map<Class<?>, Object> services;
    
    public ServiceLocator() {
        this.services = new HashMap<>();
    }
    
    public <T> void register(Class<T> serviceClass, T serviceInstance) {
        services.put(serviceClass, serviceInstance);
    }
    
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getService(Class<T> serviceClass) {
        return Optional.ofNullable((T) services.get(serviceClass));
    }
    
    @SuppressWarnings("unchecked")
    public <T> T getRequiredService(Class<T> serviceClass) {
        T service = (T) services.get(serviceClass);
        if (service == null) {
            throw new IllegalStateException("Required service not found: " + serviceClass.getName());
        }
        return service;
    }
    
    public boolean hasService(Class<?> serviceClass) {
        return services.containsKey(serviceClass);
    }
    
    public void unregister(Class<?> serviceClass) {
        services.remove(serviceClass);
    }
    
    public void clear() {
        services.clear();
    }
}