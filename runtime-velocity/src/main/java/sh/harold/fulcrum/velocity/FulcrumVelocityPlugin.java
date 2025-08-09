package sh.harold.fulcrum.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import sh.harold.fulcrum.velocity.config.ConfigLoader;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeatureManager;

import java.nio.file.Path;

@Plugin(
    id = "fulcrum",
    name = "Fulcrum Velocity",
    version = "1.5.0",
    description = "Fulcrum framework for Velocity proxy",
    authors = {"Harold"}
)
public class FulcrumVelocityPlugin {
    
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private VelocityFeatureManager featureManager;
    private ServiceLocator serviceLocator;
    private ConfigLoader configLoader;
    
    @Inject
    public FulcrumVelocityPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }
    
    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("Initializing Fulcrum Velocity...");
        
        try {
            // Initialize configuration
            this.configLoader = new ConfigLoader(dataDirectory);
            configLoader.loadConfiguration();
            
            // Initialize service locator
            this.serviceLocator = new ServiceLocator();
            serviceLocator.register(ProxyServer.class, server);
            serviceLocator.register(Logger.class, logger);
            serviceLocator.register(ConfigLoader.class, configLoader);
            serviceLocator.register(Path.class, dataDirectory);
            serviceLocator.register(FulcrumVelocityPlugin.class, this);
            
            // Initialize feature manager
            this.featureManager = new VelocityFeatureManager(serviceLocator, logger);
            
            // Load fundamental features
            featureManager.loadFundamentalFeatures();
            
            // Initialize all features
            featureManager.initializeFeatures();
            
            logger.info("Fulcrum Velocity initialized successfully");
        } catch (Exception e) {
            logger.error("Failed to initialize Fulcrum Velocity", e);
            throw new RuntimeException("Failed to initialize Fulcrum Velocity", e);
        }
    }
    
    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("Shutting down Fulcrum Velocity...");
        
        if (featureManager != null) {
            featureManager.shutdownFeatures();
        }
        
        logger.info("Fulcrum Velocity shut down successfully");
    }
    
    public ProxyServer getServer() {
        return server;
    }
    
    public Logger getLogger() {
        return logger;
    }
    
    public Path getDataDirectory() {
        return dataDirectory;
    }
    
    public ServiceLocator getServiceLocator() {
        return serviceLocator;
    }
    
    public ConfigLoader getConfigLoader() {
        return configLoader;
    }
}