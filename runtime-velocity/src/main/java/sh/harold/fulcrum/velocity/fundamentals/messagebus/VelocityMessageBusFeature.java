package sh.harold.fulcrum.velocity.fundamentals.messagebus;

import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.config.ConfigLoader;
import sh.harold.fulcrum.velocity.config.RedisConfig;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;

import java.util.UUID;

/**
 * Feature that provides message bus functionality for the Velocity proxy.
 * Supports both simple (local) and Redis-based distributed messaging.
 */
public class VelocityMessageBusFeature implements VelocityFeature {
    
    private ProxyServer proxy;
    private FulcrumVelocityPlugin plugin;
    private ConfigLoader configLoader;
    private Logger logger;
    private MessageBus messageBus;
    
    @Override
    public String getName() {
        return "MessageBus";
    }
    
    @Override
    public int getPriority() {
        // Infrastructure layer - loads first
        return 10;
    }
    
    @Override
    public boolean isEnabled() {
        return true; // Always enabled
    }
    
    @Override
    public boolean isFundamental() {
        return true; // Core infrastructure feature
    }
    
    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) {
        this.logger = logger;
        
        // Get dependencies from service locator
        this.proxy = serviceLocator.getRequiredService(ProxyServer.class);
        this.plugin = serviceLocator.getRequiredService(FulcrumVelocityPlugin.class);
        this.configLoader = serviceLocator.getRequiredService(ConfigLoader.class);
        
        if (proxy == null || plugin == null || configLoader == null) {
            throw new IllegalStateException("Required dependencies not available");
        }
        
        logger.info("Initializing MessageBus feature");
        
        // Generate server ID for this proxy instance
        String serverId = "proxy-" + UUID.randomUUID().toString().substring(0, 8);
        
        // For simplicity, always use simple message bus in Velocity
        // Redis communication is handled by backend servers
        logger.info("Using simple message bus for Velocity proxy");
        messageBus = new VelocitySimpleMessageBus(serverId, proxy);
        
        // Register message bus service
        serviceLocator.register(MessageBus.class, messageBus);
        
        // Subscribe to server lifecycle messages
        messageBus.subscribe("server.registration.response", envelope -> {
            logger.info("Received registration response");
        });
        
        messageBus.subscribe("server.heartbeat", envelope -> {
            logger.debug("Received heartbeat message");
        });
        
        logger.info("MessageBus feature initialized with server ID: {}", serverId);
    }
    
    @Override
    public void shutdown() {
        if (messageBus != null) {
            logger.info("Shutting down message bus");
            
            if (messageBus instanceof VelocitySimpleMessageBus) {
                ((VelocitySimpleMessageBus) messageBus).shutdown();
            }
        }
    }
    
}