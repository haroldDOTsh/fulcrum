package sh.harold.fulcrum.api.messagebus.impl;

import sh.harold.fulcrum.api.messagebus.adapter.MessageBusConnectionConfig;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages Redis connections for the message bus.
 * Uses reflection to avoid compile-time dependencies on Lettuce.
 * 
 * This class is stateless and only manages connection lifecycle.
 */
public class RedisConnectionManager {
    
    private static final Logger LOGGER = Logger.getLogger(RedisConnectionManager.class.getName());
    
    private final Object redisClient;
    private final Object connection;
    private final Object pubSubConnection;
    
    /**
     * Creates a new Redis connection manager.
     * Uses reflection to dynamically load and use Lettuce classes.
     *
     * @param config the connection configuration
     * @throws RuntimeException if connection cannot be established
     */
    public RedisConnectionManager(MessageBusConnectionConfig config) {
        try {
            // Load Lettuce classes dynamically
            Class<?> redisUriClass = Class.forName("io.lettuce.core.RedisURI");
            Class<?> redisClientClass = Class.forName("io.lettuce.core.RedisClient");
            
            // Build Redis URI using reflection
            Object uriBuilder = redisUriClass.getMethod("builder").invoke(null);
            Class<?> builderClass = uriBuilder.getClass();
            
            uriBuilder = builderClass.getMethod("withHost", String.class)
                .invoke(uriBuilder, config.getHost());
            uriBuilder = builderClass.getMethod("withPort", int.class)
                .invoke(uriBuilder, config.getPort());
            uriBuilder = builderClass.getMethod("withDatabase", int.class)
                .invoke(uriBuilder, config.getDatabase());
            uriBuilder = builderClass.getMethod("withTimeout", java.time.Duration.class)
                .invoke(uriBuilder, config.getConnectionTimeout());
            
            if (config.getPassword() != null && !config.getPassword().isEmpty()) {
                uriBuilder = builderClass.getMethod("withPassword", char[].class)
                    .invoke(uriBuilder, (Object) config.getPassword().toCharArray());
            }
            
            Object redisUri = builderClass.getMethod("build").invoke(uriBuilder);
            
            // Create Redis client and connections
            this.redisClient = redisClientClass.getMethod("create", redisUriClass).invoke(null, redisUri);
            this.connection = redisClient.getClass().getMethod("connect").invoke(redisClient);
            this.pubSubConnection = redisClient.getClass().getMethod("connectPubSub").invoke(redisClient);
            
            LOGGER.info("Redis connections established to " + config.getHost() + ":" + config.getPort());
        } catch (ClassNotFoundException e) {
            LOGGER.log(Level.SEVERE, "Lettuce Redis client library not found. Please ensure io.lettuce:lettuce-core is in the classpath.", e);
            throw new RuntimeException("Redis client library not available", e);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to establish Redis connections", e);
            throw new RuntimeException("Failed to establish Redis connections", e);
        }
    }
    
    /**
     * Gets the main Redis connection for commands.
     * Returns Object to avoid compile-time dependency on Lettuce.
     *
     * @return the Redis connection as Object (StatefulRedisConnection)
     */
    public Object getConnection() {
        return connection;
    }
    
    /**
     * Gets the Redis PubSub connection for messaging.
     * Returns Object to avoid compile-time dependency on Lettuce.
     *
     * @return the PubSub connection as Object (StatefulRedisPubSubConnection)
     */
    public Object getPubSubConnection() {
        return pubSubConnection;
    }
    
    /**
     * Checks if the connections are still open.
     *
     * @return true if both connections are open
     */
    public boolean isConnected() {
        try {
            Boolean connOpen = connection != null && 
                (Boolean) connection.getClass().getMethod("isOpen").invoke(connection);
            Boolean pubSubOpen = pubSubConnection != null && 
                (Boolean) pubSubConnection.getClass().getMethod("isOpen").invoke(pubSubConnection);
            return connOpen && pubSubOpen;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Closes all Redis connections and shuts down the client.
     * This method ensures proper cleanup without maintaining state.
     */
    public void shutdown() {
        try {
            // Close PubSub connection
            if (pubSubConnection != null) {
                Boolean isOpen = (Boolean) pubSubConnection.getClass().getMethod("isOpen").invoke(pubSubConnection);
                if (isOpen) {
                    pubSubConnection.getClass().getMethod("close").invoke(pubSubConnection);
                }
            }
            
            // Close main connection
            if (connection != null) {
                Boolean isOpen = (Boolean) connection.getClass().getMethod("isOpen").invoke(connection);
                if (isOpen) {
                    connection.getClass().getMethod("close").invoke(connection);
                }
            }
            
            // Shutdown client
            if (redisClient != null) {
                redisClient.getClass().getMethod("shutdown").invoke(redisClient);
            }
            
            LOGGER.info("Redis connections closed");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error closing Redis connections", e);
        }
    }
}