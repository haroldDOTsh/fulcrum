package sh.harold.fulcrum.velocity.lifecycle;

import org.slf4j.Logger;
import sh.harold.fulcrum.velocity.fundamentals.identity.VelocityIdentityFeature;
import sh.harold.fulcrum.velocity.fundamentals.messagebus.VelocityMessageBusFeature;

import java.util.*;
import java.util.stream.Collectors;

public class VelocityFeatureManager {
    
    private final ServiceLocator serviceLocator;
    private final Logger logger;
    private final Map<String, VelocityFeature> features;
    private final List<VelocityFeature> orderedFeatures;
    
    public VelocityFeatureManager(ServiceLocator serviceLocator, Logger logger) {
        this.serviceLocator = serviceLocator;
        this.logger = logger;
        this.features = new HashMap<>();
        this.orderedFeatures = new ArrayList<>();
    }
    
    public void loadFundamentalFeatures() {
        // Load fundamental features in order
        registerFeature(new VelocityIdentityFeature());
        registerFeature(new VelocityMessageBusFeature());
    }
    
    public void registerFeature(VelocityFeature feature) {
        if (features.containsKey(feature.getName())) {
            logger.warn("Feature {} is already registered", feature.getName());
            return;
        }
        
        features.put(feature.getName(), feature);
        logger.debug("Registered feature: {}", feature.getName());
    }
    
    public void initializeFeatures() throws Exception {
        // Sort features by priority and dependencies
        orderedFeatures.clear();
        orderedFeatures.addAll(sortFeaturesByDependencies());
        
        for (VelocityFeature feature : orderedFeatures) {
            if (!feature.isEnabled()) {
                logger.info("Feature {} is disabled", feature.getName());
                continue;
            }
            
            try {
                logger.info("Initializing feature: {}", feature.getName());
                feature.initialize(serviceLocator, logger);
                
                // Register the feature in service locator
                serviceLocator.register((Class<VelocityFeature>) feature.getClass(), feature);
                
                logger.info("Feature {} initialized successfully", feature.getName());
            } catch (Exception e) {
                if (feature.isFundamental()) {
                    throw new Exception("Failed to initialize fundamental feature: " + feature.getName(), e);
                } else {
                    logger.error("Failed to initialize feature: {}", feature.getName(), e);
                }
            }
        }
    }
    
    public void shutdownFeatures() {
        // Shutdown in reverse order
        Collections.reverse(orderedFeatures);
        
        for (VelocityFeature feature : orderedFeatures) {
            try {
                logger.info("Shutting down feature: {}", feature.getName());
                feature.shutdown();
            } catch (Exception e) {
                logger.error("Error shutting down feature: {}", feature.getName(), e);
            }
        }
    }
    
    private List<VelocityFeature> sortFeaturesByDependencies() {
        List<VelocityFeature> sorted = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        
        // First sort by priority
        List<VelocityFeature> prioritySorted = features.values().stream()
            .sorted((a, b) -> Integer.compare(b.getPriority(), a.getPriority()))
            .collect(Collectors.toList());
        
        // Then topological sort for dependencies
        for (VelocityFeature feature : prioritySorted) {
            visitFeature(feature, sorted, visited, visiting);
        }
        
        return sorted;
    }
    
    private void visitFeature(VelocityFeature feature, List<VelocityFeature> sorted,
                              Set<String> visited, Set<String> visiting) {
        String name = feature.getName();
        
        if (visited.contains(name)) {
            return;
        }
        
        if (visiting.contains(name)) {
            throw new IllegalStateException("Circular dependency detected for feature: " + name);
        }
        
        visiting.add(name);
        
        for (String dependency : feature.getDependencies()) {
            VelocityFeature depFeature = features.get(dependency);
            if (depFeature != null) {
                visitFeature(depFeature, sorted, visited, visiting);
            } else {
                logger.warn("Feature {} depends on missing feature: {}", name, dependency);
            }
        }
        
        visiting.remove(name);
        visited.add(name);
        sorted.add(feature);
    }
    
    public Optional<VelocityFeature> getFeature(String name) {
        return Optional.ofNullable(features.get(name));
    }
    
    public <T extends VelocityFeature> Optional<T> getFeature(Class<T> featureClass) {
        return features.values().stream()
            .filter(featureClass::isInstance)
            .map(featureClass::cast)
            .findFirst();
    }
}