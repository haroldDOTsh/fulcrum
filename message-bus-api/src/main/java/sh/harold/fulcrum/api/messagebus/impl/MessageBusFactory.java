package sh.harold.fulcrum.api.messagebus.impl;

import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusAdapter;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusConnectionConfig;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Factory for creating MessageBus instances based on configuration.
 * Automatically selects the appropriate implementation based on the
 * connection configuration type.
 * 
 * This factory is completely stateless and provides fail-safe
 * fallback to in-memory implementation when Redis is unavailable.
 */
public class MessageBusFactory {
    
    private static final Logger LOGGER = Logger.getLogger(MessageBusFactory.class.getName());
    
    /**
     * Creates a MessageBus instance based on the adapter's configuration.
     * Will automatically fall back to in-memory if Redis is unavailable.
     *
     * @param adapter the message bus adapter providing platform-specific functionality
     * @return a configured MessageBus instance
     * @throws IllegalArgumentException if the configuration type is unsupported
     */
    public static MessageBus create(MessageBusAdapter adapter) {
        MessageBusConnectionConfig config = adapter.getConnectionConfig();
        MessageBusConnectionConfig.MessageBusType type = config.getType();
        
        LOGGER.info("Creating MessageBus of type: " + type);
        
        switch (type) {
            case IN_MEMORY:
                return createInMemoryMessageBus(adapter);
                
            case REDIS:
                return createRedisMessageBus(adapter);
                
            default:
                throw new IllegalArgumentException("Unsupported message bus type: " + type);
        }
    }
    
    /**
     * Creates an in-memory message bus instance.
     */
    private static MessageBus createInMemoryMessageBus(MessageBusAdapter adapter) {
        try {
            return new InMemoryMessageBus(adapter);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to create InMemoryMessageBus", e);
            throw new RuntimeException("Failed to create InMemoryMessageBus", e);
        }
    }
    
    /**
     * Creates a Redis-based message bus instance with automatic fallback.
     */
    private static MessageBus createRedisMessageBus(MessageBusAdapter adapter) {
        try {
            // Check if Redis/Lettuce is available
            Class.forName("io.lettuce.core.RedisClient");
            
            // Create Redis message bus
            return new RedisMessageBus(adapter);
        } catch (ClassNotFoundException e) {
            LOGGER.log(Level.SEVERE, 
                "Redis support requested but Lettuce library not found. " +
                "Please ensure io.lettuce:lettuce-core is in the classpath. " +
                "Falling back to InMemoryMessageBus.", e);
            
            // Fallback to in-memory
            LOGGER.warning("Falling back to InMemoryMessageBus due to missing Redis support");
            return createInMemoryMessageBus(adapter);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to create RedisMessageBus", e);
            
            // If Redis fails to initialize, fall back to in-memory
            if (adapter.getConnectionConfig().getType() == MessageBusConnectionConfig.MessageBusType.REDIS) {
                LOGGER.warning("Redis initialization failed, falling back to InMemoryMessageBus");
                return createInMemoryMessageBus(adapter);
            }
            
            throw new RuntimeException("Failed to create RedisMessageBus", e);
        }
    }
    
    /**
     * Checks if Redis support is available.
     *
     * @return true if Redis/Lettuce library is available
     */
    public static boolean isRedisAvailable() {
        try {
            Class.forName("io.lettuce.core.RedisClient");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
    
    /**
     * Gets a description of available message bus types.
     *
     * @return a string describing available types
     */
    public static String getAvailableTypes() {
        StringBuilder sb = new StringBuilder();
        sb.append("Available message bus types:\n");
        sb.append("  - IN_MEMORY: Simple in-memory implementation (always available)\n");
        
        if (isRedisAvailable()) {
            sb.append("  - REDIS: Redis-based distributed implementation (available)\n");
        } else {
            sb.append("  - REDIS: Redis-based distributed implementation (NOT AVAILABLE - missing lettuce-core)\n");
        }
        
        return sb.toString();
    }
}