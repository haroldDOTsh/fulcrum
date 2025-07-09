package sh.harold.fulcrum.runtime.config;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration class for dirty data caching system.
 * 
 * This class defines the configuration for the dirty data tracking system,
 * including cache type selection, Redis settings, and fallback behavior.
 */
public class DirtyCacheConfig {
    
    /**
     * Enumeration of available cache types.
     */
    public enum CacheType {
        /**
         * In-memory cache - fast but not persistent across restarts.
         */
        MEMORY("memory"),
        
        /**
         * Redis cache - persistent and distributed but requires Redis server.
         */
        REDIS("redis");
        
        private final String configName;
        
        CacheType(String configName) {
            this.configName = configName;
        }
        
        public String getConfigName() {
            return configName;
        }
        
        /**
         * Gets cache type from configuration name.
         * 
         * @param configName The configuration name
         * @return The cache type
         * @throws IllegalArgumentException if the config name is invalid
         */
        public static CacheType fromConfigName(String configName) {
            for (CacheType type : values()) {
                if (type.configName.equalsIgnoreCase(configName)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Invalid cache type: " + configName);
        }
    }
    
    // Default values
    private static final CacheType DEFAULT_CACHE_TYPE = CacheType.MEMORY;
    private static final boolean DEFAULT_FALLBACK_TO_MEMORY = true;
    private static final Duration DEFAULT_ENTRY_TTL = Duration.ofHours(1);
    private static final Duration DEFAULT_HEALTH_CHECK_INTERVAL = Duration.ofMinutes(5);
    
    // Configuration fields
    private final CacheType cacheType;
    private final boolean fallbackToMemory;
    private final Duration entryTtl;
    private final Duration healthCheckInterval;
    private final RedisSettings redisSettings;
    
    private DirtyCacheConfig(Builder builder) {
        this.cacheType = builder.cacheType;
        this.fallbackToMemory = builder.fallbackToMemory;
        this.entryTtl = builder.entryTtl;
        this.healthCheckInterval = builder.healthCheckInterval;
        this.redisSettings = builder.redisSettings;
    }
    
    /**
     * Creates a new builder for dirty cache configuration.
     * 
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Creates a default dirty cache configuration.
     * 
     * @return Default configuration
     */
    public static DirtyCacheConfig defaults() {
        return new Builder().build();
    }
    
    // Getters
    public CacheType getCacheType() { return cacheType; }
    public boolean shouldFallbackToMemory() { return fallbackToMemory; }
    public Duration getEntryTtl() { return entryTtl; }
    public Duration getHealthCheckInterval() { return healthCheckInterval; }
    public RedisSettings getRedisSettings() { return redisSettings; }
    
    @Override
    public String toString() {
        return "DirtyCacheConfig{" +
                "cacheType=" + cacheType +
                ", fallbackToMemory=" + fallbackToMemory +
                ", entryTtl=" + entryTtl +
                ", healthCheckInterval=" + healthCheckInterval +
                ", redisSettings=" + redisSettings +
                '}';
    }
    
    /**
     * Redis-specific settings nested class.
     */
    public static class RedisSettings {
        private final String host;
        private final int port;
        private final int database;
        private final String password;
        private final Duration connectionTimeout;
        private final Duration retryDelay;
        private final int maxRetries;
        private final int maxConnections;
        private final int maxIdleConnections;
        private final int minIdleConnections;
        
        private RedisSettings(RedisSettingsBuilder builder) {
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
         * Creates a new builder for Redis settings.
         * 
         * @return A new builder instance
         */
        public static RedisSettingsBuilder builder() {
            return new RedisSettingsBuilder();
        }
        
        /**
         * Creates default Redis settings.
         * 
         * @return Default Redis settings
         */
        public static RedisSettings defaults() {
            return new RedisSettingsBuilder().build();
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
            return "RedisSettings{" +
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
    }
    
    /**
     * Builder class for creating dirty cache configurations.
     */
    public static class Builder {
        private CacheType cacheType = DEFAULT_CACHE_TYPE;
        private boolean fallbackToMemory = DEFAULT_FALLBACK_TO_MEMORY;
        private Duration entryTtl = DEFAULT_ENTRY_TTL;
        private Duration healthCheckInterval = DEFAULT_HEALTH_CHECK_INTERVAL;
        private RedisSettings redisSettings = RedisSettings.defaults();
        
        /**
         * Sets the cache type.
         * 
         * @param cacheType The cache type
         * @return This builder
         */
        public Builder cacheType(CacheType cacheType) {
            this.cacheType = Objects.requireNonNull(cacheType, "cacheType cannot be null");
            return this;
        }
        
        /**
         * Sets whether to fallback to memory cache if Redis is unavailable.
         * 
         * @param fallbackToMemory Whether to fallback to memory
         * @return This builder
         */
        public Builder fallbackToMemory(boolean fallbackToMemory) {
            this.fallbackToMemory = fallbackToMemory;
            return this;
        }
        
        /**
         * Sets the TTL for cache entries.
         * 
         * @param entryTtl The entry TTL
         * @return This builder
         */
        public Builder entryTtl(Duration entryTtl) {
            this.entryTtl = Objects.requireNonNull(entryTtl, "entryTtl cannot be null");
            return this;
        }
        
        /**
         * Sets the health check interval.
         * 
         * @param healthCheckInterval The health check interval
         * @return This builder
         */
        public Builder healthCheckInterval(Duration healthCheckInterval) {
            this.healthCheckInterval = Objects.requireNonNull(healthCheckInterval, "healthCheckInterval cannot be null");
            return this;
        }
        
        /**
         * Sets the Redis settings.
         * 
         * @param redisSettings The Redis settings
         * @return This builder
         */
        public Builder redisSettings(RedisSettings redisSettings) {
            this.redisSettings = Objects.requireNonNull(redisSettings, "redisSettings cannot be null");
            return this;
        }
        
        /**
         * Builds the dirty cache configuration.
         * 
         * @return The built configuration
         */
        public DirtyCacheConfig build() {
            return new DirtyCacheConfig(this);
        }
    }
    
    /**
     * Builder class for Redis settings.
     */
    public static class RedisSettingsBuilder {
        private String host = "localhost";
        private int port = 6379;
        private int database = 0;
        private String password;
        private Duration connectionTimeout = Duration.ofSeconds(5);
        private Duration retryDelay = Duration.ofMillis(500);
        private int maxRetries = 3;
        private int maxConnections = 20;
        private int maxIdleConnections = 10;
        private int minIdleConnections = 5;
        
        public DirtyCacheConfig.RedisSettingsBuilder host(String host) {
            this.host = Objects.requireNonNull(host, "host cannot be null");
            return this;
        }
        
        public DirtyCacheConfig.RedisSettingsBuilder port(int port) {
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("Port must be between 1 and 65535");
            }
            this.port = port;
            return this;
        }
        
        public DirtyCacheConfig.RedisSettingsBuilder database(int database) {
            if (database < 0) {
                throw new IllegalArgumentException("Database must be non-negative");
            }
            this.database = database;
            return this;
        }
        
        public DirtyCacheConfig.RedisSettingsBuilder password(String password) {
            this.password = password;
            return this;
        }
        
        public DirtyCacheConfig.RedisSettingsBuilder connectionTimeout(Duration connectionTimeout) {
            this.connectionTimeout = Objects.requireNonNull(connectionTimeout, "connectionTimeout cannot be null");
            return this;
        }
        
        public DirtyCacheConfig.RedisSettingsBuilder retryDelay(Duration retryDelay) {
            this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay cannot be null");
            return this;
        }
        
        public DirtyCacheConfig.RedisSettingsBuilder maxRetries(int maxRetries) {
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries must be non-negative");
            }
            this.maxRetries = maxRetries;
            return this;
        }
        
        public DirtyCacheConfig.RedisSettingsBuilder maxConnections(int maxConnections) {
            if (maxConnections <= 0) {
                throw new IllegalArgumentException("maxConnections must be positive");
            }
            this.maxConnections = maxConnections;
            return this;
        }
        
        public DirtyCacheConfig.RedisSettingsBuilder maxIdleConnections(int maxIdleConnections) {
            if (maxIdleConnections < 0) {
                throw new IllegalArgumentException("maxIdleConnections must be non-negative");
            }
            this.maxIdleConnections = maxIdleConnections;
            return this;
        }
        
        public DirtyCacheConfig.RedisSettingsBuilder minIdleConnections(int minIdleConnections) {
            if (minIdleConnections < 0) {
                throw new IllegalArgumentException("minIdleConnections must be non-negative");
            }
            this.minIdleConnections = minIdleConnections;
            return this;
        }
        
        public RedisSettings build() {
            // Validate configuration
            if (minIdleConnections > maxIdleConnections) {
                throw new IllegalArgumentException("minIdleConnections cannot be greater than maxIdleConnections");
            }
            if (maxIdleConnections > maxConnections) {
                throw new IllegalArgumentException("maxIdleConnections cannot be greater than maxConnections");
            }
            
            return new RedisSettings(this);
        }
    }
}