package sh.harold.fulcrum.velocity.fundamentals.messagebus;

import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.config.ConfigLoader;
import sh.harold.fulcrum.velocity.config.RedisConfig;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        
        // Generate server ID for this proxy instance with fulcrum-velocity format
        String serverId = generateServerId();
        
        // Load message bus configuration
        MessageBusConfig config = configLoader.getConfig(MessageBusConfig.class);
        if (config == null) {
            config = new MessageBusConfig();
            config.setMode("redis"); // Default to Redis for production
        }
        
        // Create appropriate message bus based on configuration
        if (config.isRedisMode()) {
            RedisConfig redisConfig = configLoader.getConfig(RedisConfig.class);
            if (redisConfig == null) {
                logger.warn("Redis config not found, falling back to simple message bus");
                messageBus = new VelocitySimpleMessageBus(serverId, proxy);
            } else {
                logger.info("Initializing Redis message bus for Velocity proxy with ID: {}", serverId);
                messageBus = new VelocityRedisMessageBus(serverId, proxy, redisConfig);
            }
        } else {
            logger.info("Using simple message bus for Velocity proxy with ID: {}", serverId);
            messageBus = new VelocitySimpleMessageBus(serverId, proxy);
        }
        
        // Register message bus service BEFORE other features try to use it
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
    
    private String generateServerId() {
        Path dataPath = plugin.getDataDirectory();
        Path idFile = dataPath.resolve("velocity-id.txt");
        
        // Check for persisted ID
        if (Files.exists(idFile)) {
            try {
                String persistedId = Files.readString(idFile).trim();
                if (!persistedId.isEmpty()) {
                    logger.info("Using persisted proxy ID: {}", persistedId);
                    return persistedId;
                }
            } catch (IOException e) {
                logger.warn("Failed to read persisted ID: {}", e.getMessage());
            }
        }
        
        // Generate new ID with fulcrum-velocity format
        int index = 0;
        Path indexFile = dataPath.resolve("velocity-index.txt");
        if (Files.exists(indexFile)) {
            try {
                String indexStr = Files.readString(indexFile).trim();
                index = Integer.parseInt(indexStr);
            } catch (IOException | NumberFormatException e) {
                logger.warn("Failed to read velocity index, using 0: {}", e.getMessage());
            }
        }
        
        String newId = "fulcrum-velocity-" + index;
        
        // Persist the ID
        try {
            Files.createDirectories(dataPath);
            Files.writeString(idFile, newId);
            // Increment and save index for next proxy
            Files.writeString(indexFile, String.valueOf(index + 1));
            logger.info("Generated and persisted new proxy ID: {}", newId);
        } catch (IOException e) {
            logger.warn("Failed to persist proxy ID: {}", e.getMessage());
        }
        
        return newId;
    }
    
    @Override
    public void shutdown() {
        if (messageBus != null) {
            logger.info("Shutting down message bus");
            
            if (messageBus instanceof VelocitySimpleMessageBus) {
                ((VelocitySimpleMessageBus) messageBus).shutdown();
            } else if (messageBus instanceof VelocityRedisMessageBus) {
                ((VelocityRedisMessageBus) messageBus).shutdown();
            }
        }
    }
    
}