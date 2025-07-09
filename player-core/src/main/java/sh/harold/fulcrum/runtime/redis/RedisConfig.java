package sh.harold.fulcrum.runtime.redis;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration class for Redis connection settings.
 * 
 * This class holds all the configuration parameters needed to establish
 * and manage Redis connections, including connection pooling settings,
 * retry policies, and authentication details.
 */
public class RedisConfig {
    
    // Default values
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 6379;
    private static final int DEFAULT_DATABASE = 0;
    private static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_RETRY_DELAY = Duration.ofMillis(500);
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final int DEFAULT_MAX_CONNECTIONS = 20;
    private static final int DEFAULT_MAX_IDLE_CONNECTIONS = 10;
    private static final int DEFAULT_MIN_IDLE_CONNECTIONS = 5;
    
    // Connection settings
    private final String host;
    private final int port;
    private final int database;
    private final String password;
    private final Duration connectionTimeout;
    
    // Retry settings
    private final Duration retryDelay;
    private final int maxRetries;
    
    // Pool settings
    private final int maxConnections;
    private final int maxIdleConnections;
    private final int minIdleConnections;
    
    private RedisConfig(Builder builder) {
        this.host = builder.host;
        this.port = builder.port;
        this.database = builder.database;
        this.password = builder.password;
        this.connectionTimeout = builder.connectionTimeout;
        this.retryDelay = builder.retryDelay;
        this.maxRetries = builder.maxRetries;
        this.maxConnections = builder.maxConnections;
        this.maxIdleConnections = builder.maxIdleConnections;
        this.minIdleConnections = builder.minIdleConnections;
    }
    
    /**
     * Creates a new builder for Redis configuration.
     * 
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Creates a Redis configuration with default values.
     * 
     * @return Default Redis configuration
     */
    public static RedisConfig defaults() {
        return new Builder().build();
    }
    
    // Getters
    public String getHost() { return host; }
    public int getPort() { return port; }
    public int getDatabase() { return database; }
    public String getPassword() { return password; }
    public Duration getConnectionTimeout() { return connectionTimeout; }
    public Duration getRetryDelay() { return retryDelay; }
    public int getMaxRetries() { return maxRetries; }
    public int getMaxConnections() { return maxConnections; }
    public int getMaxIdleConnections() { return maxIdleConnections; }
    public int getMinIdleConnections() { return minIdleConnections; }
    
    @Override
    public String toString() {
        return "RedisConfig{" +
                "host='" + host + '\'' +
                ", port=" + port +
                ", database=" + database +
                ", hasPassword=" + (password != null && !password.isEmpty()) +
                ", connectionTimeout=" + connectionTimeout +
                ", retryDelay=" + retryDelay +
                ", maxRetries=" + maxRetries +
                ", maxConnections=" + maxConnections +
                ", maxIdleConnections=" + maxIdleConnections +
                ", minIdleConnections=" + minIdleConnections +
                '}';
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RedisConfig that = (RedisConfig) o;
        return port == that.port &&
                database == that.database &&
                maxRetries == that.maxRetries &&
                maxConnections == that.maxConnections &&
                maxIdleConnections == that.maxIdleConnections &&
                minIdleConnections == that.minIdleConnections &&
                Objects.equals(host, that.host) &&
                Objects.equals(password, that.password) &&
                Objects.equals(connectionTimeout, that.connectionTimeout) &&
                Objects.equals(retryDelay, that.retryDelay);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(host, port, database, password, connectionTimeout, 
                          retryDelay, maxRetries, maxConnections, maxIdleConnections, 
                          minIdleConnections);
    }
    
    /**
     * Builder class for creating Redis configurations.
     */
    public static class Builder {
        private String host = DEFAULT_HOST;
        private int port = DEFAULT_PORT;
        private int database = DEFAULT_DATABASE;
        private String password;
        private Duration connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;
        private Duration retryDelay = DEFAULT_RETRY_DELAY;
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private int maxConnections = DEFAULT_MAX_CONNECTIONS;
        private int maxIdleConnections = DEFAULT_MAX_IDLE_CONNECTIONS;
        private int minIdleConnections = DEFAULT_MIN_IDLE_CONNECTIONS;
        
        /**
         * Sets the Redis host.
         * 
         * @param host The Redis host
         * @return This builder
         */
        public Builder host(String host) {
            this.host = Objects.requireNonNull(host, "host cannot be null");
            return this;
        }
        
        /**
         * Sets the Redis port.
         * 
         * @param port The Redis port
         * @return This builder
         */
        public Builder port(int port) {
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("Port must be between 1 and 65535");
            }
            this.port = port;
            return this;
        }
        
        /**
         * Sets the Redis database number.
         * 
         * @param database The database number
         * @return This builder
         */
        public Builder database(int database) {
            if (database < 0) {
                throw new IllegalArgumentException("Database must be non-negative");
            }
            this.database = database;
            return this;
        }
        
        /**
         * Sets the Redis password.
         * 
         * @param password The password
         * @return This builder
         */
        public Builder password(String password) {
            this.password = password;
            return this;
        }
        
        /**
         * Sets the connection timeout.
         * 
         * @param connectionTimeout The connection timeout
         * @return This builder
         */
        public Builder connectionTimeout(Duration connectionTimeout) {
            this.connectionTimeout = Objects.requireNonNull(connectionTimeout, "connectionTimeout cannot be null");
            return this;
        }
        
        /**
         * Sets the retry delay.
         * 
         * @param retryDelay The retry delay
         * @return This builder
         */
        public Builder retryDelay(Duration retryDelay) {
            this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay cannot be null");
            return this;
        }
        
        /**
         * Sets the maximum number of retries.
         * 
         * @param maxRetries The maximum number of retries
         * @return This builder
         */
        public Builder maxRetries(int maxRetries) {
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries must be non-negative");
            }
            this.maxRetries = maxRetries;
            return this;
        }
        
        /**
         * Sets the maximum number of connections in the pool.
         * 
         * @param maxConnections The maximum number of connections
         * @return This builder
         */
        public Builder maxConnections(int maxConnections) {
            if (maxConnections <= 0) {
                throw new IllegalArgumentException("maxConnections must be positive");
            }
            this.maxConnections = maxConnections;
            return this;
        }
        
        /**
         * Sets the maximum number of idle connections in the pool.
         * 
         * @param maxIdleConnections The maximum number of idle connections
         * @return This builder
         */
        public Builder maxIdleConnections(int maxIdleConnections) {
            if (maxIdleConnections < 0) {
                throw new IllegalArgumentException("maxIdleConnections must be non-negative");
            }
            this.maxIdleConnections = maxIdleConnections;
            return this;
        }
        
        /**
         * Sets the minimum number of idle connections in the pool.
         * 
         * @param minIdleConnections The minimum number of idle connections
         * @return This builder
         */
        public Builder minIdleConnections(int minIdleConnections) {
            if (minIdleConnections < 0) {
                throw new IllegalArgumentException("minIdleConnections must be non-negative");
            }
            this.minIdleConnections = minIdleConnections;
            return this;
        }
        
        /**
         * Builds the Redis configuration.
         * 
         * @return The built configuration
         */
        public RedisConfig build() {
            // Validate configuration
            if (minIdleConnections > maxIdleConnections) {
                throw new IllegalArgumentException("minIdleConnections cannot be greater than maxIdleConnections");
            }
            if (maxIdleConnections > maxConnections) {
                throw new IllegalArgumentException("maxIdleConnections cannot be greater than maxConnections");
            }
            
            return new RedisConfig(this);
        }
    }
}