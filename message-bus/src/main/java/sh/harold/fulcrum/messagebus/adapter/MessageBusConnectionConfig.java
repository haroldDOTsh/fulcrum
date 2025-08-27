package sh.harold.fulcrum.messagebus.adapter;

import java.time.Duration;

/**
 * Connection configuration for the message bus.
 * Provides all necessary configuration for different message bus implementations.
 */
public interface MessageBusConnectionConfig {
    
    /**
     * The type of connection to use.
     */
    enum ConnectionType {
        REDIS,
        IN_MEMORY
    }
    
    /**
     * Get the connection type.
     * @return The connection type
     */
    ConnectionType getType();
    
    /**
     * Get Redis connection details (when type is REDIS).
     * @return Redis connection details or null if not using Redis
     */
    RedisConnectionDetails getRedisDetails();
    
    /**
     * Check if development mode is enabled.
     * Development mode may use simplified implementations.
     * @return true if in development mode
     */
    boolean isDevelopmentMode();
    
    /**
     * Get message timeout duration for request/response operations.
     * @return Timeout duration
     */
    Duration getMessageTimeout();
    
    /**
     * Redis-specific connection details.
     */
    interface RedisConnectionDetails {
        /**
         * Get Redis host.
         * @return Redis host address
         */
        String getHost();
        
        /**
         * Get Redis port.
         * @return Redis port number
         */
        int getPort();
        
        /**
         * Get Redis password.
         * @return Redis password or null if no auth
         */
        String getPassword();
        
        /**
         * Get Redis database index.
         * @return Database index (0-15)
         */
        int getDatabase();
        
        /**
         * Get connection timeout.
         * @return Connection timeout duration
         */
        Duration getConnectionTimeout();
        
        /**
         * Get maximum number of connections in pool.
         * @return Maximum connections
         */
        int getMaxConnections();
    }
}