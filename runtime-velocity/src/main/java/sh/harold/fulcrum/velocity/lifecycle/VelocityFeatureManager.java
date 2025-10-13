package sh.harold.fulcrum.velocity.lifecycle;

import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.config.ConfigLoader;
import sh.harold.fulcrum.velocity.config.ServerLifecycleConfig;
import sh.harold.fulcrum.velocity.fundamentals.commands.VelocityCommandFeature;
import sh.harold.fulcrum.velocity.fundamentals.data.VelocityDataAPIFeature;
import sh.harold.fulcrum.velocity.fundamentals.data.VelocityPlayerDataFeature;
import sh.harold.fulcrum.velocity.fundamentals.family.SlotFamilyFeature;
import sh.harold.fulcrum.velocity.fundamentals.identity.VelocityIdentityFeature;
import sh.harold.fulcrum.velocity.fundamentals.lifecycle.VelocityServerLifecycleFeature;
import sh.harold.fulcrum.velocity.fundamentals.messagebus.VelocityMessageBusFeature;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

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
        // Server lifecycle should be loaded first as it provides core services
        // Get required dependencies from service locator
        ProxyServer proxyServer = serviceLocator.getRequiredService(ProxyServer.class);
        FulcrumVelocityPlugin plugin = serviceLocator.getRequiredService(FulcrumVelocityPlugin.class);
        ConfigLoader configLoader = serviceLocator.getRequiredService(ConfigLoader.class);

        // Get server lifecycle config
        ServerLifecycleConfig lifecycleConfig = configLoader.getConfig(ServerLifecycleConfig.class);
        if (lifecycleConfig == null) {
            lifecycleConfig = new ServerLifecycleConfig(); // Use defaults
        }

        // Check development mode from config
        boolean developmentMode = configLoader.get("development-mode", false);
        if (developmentMode) {
            logger.warn("Development mode is enabled - server registrations and heartbeats will be disabled");
        }

        // Create scheduled executor for heartbeats
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "Fulcrum-Lifecycle");
            t.setDaemon(true);
            return t;
        });

        // Register features in dependency order
        registerFeature(new VelocityIdentityFeature());
        registerFeature(new VelocityMessageBusFeature());
        registerFeature(new VelocityDataAPIFeature());
        registerFeature(new SlotFamilyFeature());
        registerFeature(new VelocityServerLifecycleFeature(proxyServer, logger, lifecycleConfig, scheduler, developmentMode));
        registerFeature(new sh.harold.fulcrum.velocity.fundamentals.routing.PlayerRoutingFeature());
        registerFeature(new VelocityPlayerDataFeature());
        registerFeature(new VelocityCommandFeature());
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
        // Sort features by priority (lower priority value loads first)
        orderedFeatures.clear();
        orderedFeatures.addAll(features.values());
        orderedFeatures.sort((a, b) -> Integer.compare(a.getPriority(), b.getPriority()));

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
                logger.error("Failed to initialize feature: {}", feature.getName(), e);
                // Continue with other features instead of failing completely
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
