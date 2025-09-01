package sh.harold.fulcrum.api.messagebus.impl;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusConnectionConfig;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages Redis connections for the message bus.
 * Uses Lettuce client library directly for cleaner, type-safe code.
 */
public class RedisConnectionManager {
    
    private static final Logger LOGGER = Logger.getLogger(RedisConnectionManager.class.getName());
    
    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final StatefulRedisPubSubConnection<String, String> pubSubConnection;
    
    /**
     * Creates a new Redis connection manager.
     *
     * @param config the connection configuration
     * @throws RuntimeException if connection cannot be established
     */
    public RedisConnectionManager(MessageBusConnectionConfig config) {
        try {
            // Build Redis URI
            RedisURI.Builder uriBuilder = RedisURI.builder()
                .withHost(config.getHost())
                .withPort(config.getPort())
                .withDatabase(config.getDatabase())
                .withTimeout(config.getConnectionTimeout());
            
            if (config.getPassword() != null && !config.getPassword().isEmpty()) {
                uriBuilder.withPassword(config.getPassword().toCharArray());
            }
            
            RedisURI redisUri = uriBuilder.build();
            
            // Create Redis client and connections
            this.redisClient = RedisClient.create(redisUri);
            this.connection = redisClient.connect();
            this.pubSubConnection = redisClient.connectPubSub();
            
            LOGGER.info("Redis connections established to " + config.getHost() + ":" + config.getPort());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to establish Redis connections", e);
            throw new RuntimeException("Failed to establish Redis connections", e);
        }
    }
    
    /**
     * Gets the main Redis connection for commands.
     *
     * @return the Redis connection
     */
    public StatefulRedisConnection<String, String> getConnection() {
        return connection;
    }
    
    /**
     * Gets the Redis PubSub connection for messaging.
     *
     * @return the PubSub connection
     */
    public StatefulRedisPubSubConnection<String, String> getPubSubConnection() {
        return pubSubConnection;
    }
    
    /**
     * Checks if the connections are still open.
     *
     * @return true if both connections are open
     */
    public boolean isConnected() {
        return connection != null && connection.isOpen() &&
               pubSubConnection != null && pubSubConnection.isOpen();
    }
    
    /**
     * Closes all Redis connections and shuts down the client.
     */
    public void shutdown() {
        try {
            // Close PubSub connection
            if (pubSubConnection != null && pubSubConnection.isOpen()) {
                pubSubConnection.close();
            }
            
            // Close main connection
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
            
            // Shutdown client
            if (redisClient != null) {
                redisClient.shutdown();
            }
            
            LOGGER.info("Redis connections closed");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error closing Redis connections", e);
        }
    }
}