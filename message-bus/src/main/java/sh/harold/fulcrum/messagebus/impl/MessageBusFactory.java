package sh.harold.fulcrum.messagebus.impl;

import sh.harold.fulcrum.messagebus.MessageBus;
import sh.harold.fulcrum.messagebus.adapter.MessageBusAdapter;
import sh.harold.fulcrum.messagebus.adapter.MessageBusConnectionConfig;
import sh.harold.fulcrum.messagebus.adapter.MessageBusConnectionConfig.ConnectionType;
import sh.harold.fulcrum.messagebus.redis.RedisConnectionManager;

/**
 * Factory for creating MessageBus instances.
 * Creates appropriate MessageBus implementations based on adapter configuration.
 */
public class MessageBusFactory {
    
    private MessageBusFactory() {
        // Prevent instantiation - static factory methods only
    }
    
    /**
     * Create a MessageBus instance based on the adapter configuration.
     * 
     * @param adapter Platform-specific adapter
     * @return Configured MessageBus instance
     * @throws IllegalArgumentException if configuration is invalid
     */
    public static MessageBus create(MessageBusAdapter adapter) {
        if (adapter == null) {
            throw new IllegalArgumentException("MessageBusAdapter cannot be null");
        }
        
        var config = adapter.getConnectionConfig();
        if (config == null) {
            throw new IllegalArgumentException("Connection configuration cannot be null");
        }
        
        // Development mode always uses in-memory implementation
        if (config.isDevelopmentMode()) {
            adapter.getLogger().info("Creating InMemoryMessageBus (development mode)");
            return new InMemoryMessageBus(adapter);
        }
        
        // Production mode based on connection type
        switch (config.getType()) {
            case IN_MEMORY:
                adapter.getLogger().info("Creating InMemoryMessageBus");
                return new InMemoryMessageBus(adapter);
                
            case REDIS:
                var redisDetails = config.getRedisDetails();
                if (redisDetails == null) {
                    throw new IllegalArgumentException(
                        "Redis connection details required when using REDIS connection type"
                    );
                }
                
                adapter.getLogger().info(String.format(
                    "Creating RedisMessageBus connecting to %s:%d",
                    redisDetails.getHost(),
                    redisDetails.getPort()
                ));
                
                var redisManager = new RedisConnectionManager(
                    redisDetails,
                    adapter.getLogger()
                );
                return new RedisMessageBus(adapter, redisManager);
                
            default:
                throw new IllegalArgumentException(
                    "Unknown connection type: " + config.getType()
                );
        }
    }
    
    /**
     * Create a MessageBus instance with a custom codec registry.
     * 
     * @param adapter Platform-specific adapter
     * @param codecRegistry Custom codec registry to use
     * @return Configured MessageBus instance with custom codec registry
     */
    public static MessageBus createWithCodecRegistry(
            MessageBusAdapter adapter,
            DynamicCodecRegistry codecRegistry) {
        
        MessageBus messageBus = create(adapter);
        
        // If the implementation supports setting a custom codec registry
        if (messageBus instanceof AbstractMessageBus) {
            ((AbstractMessageBus) messageBus).setCodecRegistry(codecRegistry);
        }
        
        return messageBus;
    }
}