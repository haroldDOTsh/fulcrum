package sh.harold.fulcrum.runtime.cache;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.data.dirty.DirtyDataCache;
import sh.harold.fulcrum.api.data.dirty.InMemoryDirtyDataCache;
import sh.harold.fulcrum.api.data.dirty.RedisDirtyDataCache;
import sh.harold.fulcrum.runtime.config.DirtyCacheConfig;
import sh.harold.fulcrum.runtime.redis.JedisRedisOperations;
import sh.harold.fulcrum.runtime.redis.RedisConfig;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Factory for creating dirty data cache instances based on configuration.
 * 
 * This factory handles the instantiation of different cache implementations
 * and provides fallback behavior when the primary cache is unavailable.
 */
public class DirtyCacheFactory {
    
    private static final Logger LOGGER = Logger.getLogger(DirtyCacheFactory.class.getName());
    
    /**
     * Creates a dirty data cache instance based on the provided configuration.
     * 
     * @param config The cache configuration
     * @param plugin The plugin instance for logging
     * @return A dirty data cache instance
     */
    public static DirtyDataCache createCache(DirtyCacheConfig config, JavaPlugin plugin) {
        Logger logger = plugin.getLogger();
        
        try {
            switch (config.getCacheType()) {
                case REDIS:
                    return createRedisCache(config, logger);
                case MEMORY:
                default:
                    return createMemoryCache(config, logger);
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to create primary cache, falling back to memory cache", e);
            return createMemoryCache(config, logger);
        }
    }
    
    /**
     * Creates a Redis-based dirty data cache.
     * 
     * @param config The cache configuration
     * @param logger The logger instance
     * @return A Redis dirty data cache
     */
    private static DirtyDataCache createRedisCache(DirtyCacheConfig config, Logger logger) {
        logger.info("Initializing Redis dirty data cache...");
        
        try {
            // Convert configuration to Redis config
            RedisConfig redisConfig = convertToRedisConfig(config.getRedisSettings());
            
            // Create Redis operations
            JedisRedisOperations redisOperations = new JedisRedisOperations(redisConfig);
            
            // Create Redis cache
            long ttlSeconds = config.getEntryTtl().getSeconds();
            RedisDirtyDataCache redisCache = new RedisDirtyDataCache(redisOperations, ttlSeconds);
            
            // Test Redis availability
            if (redisOperations.isAvailable()) {
                logger.info("Redis dirty data cache initialized successfully");
                
                // Start health check task if configured
                if (config.getHealthCheckInterval().toMillis() > 0) {
                    startHealthCheckTask(redisOperations, config, logger);
                }
                
                return redisCache;
            } else {
                logger.warning("Redis is not available");
                return handleRedisUnavailable(config, logger);
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to initialize Redis cache", e);
            return handleRedisUnavailable(config, logger);
        }
    }
    
    /**
     * Creates an in-memory dirty data cache.
     * 
     * @param config The cache configuration
     * @param logger The logger instance
     * @return An in-memory dirty data cache
     */
    private static DirtyDataCache createMemoryCache(DirtyCacheConfig config, Logger logger) {
        logger.info("Initializing in-memory dirty data cache...");
        
        try {
            InMemoryDirtyDataCache memoryCache = new InMemoryDirtyDataCache();
            logger.info("In-memory dirty data cache initialized successfully");
            return memoryCache;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to initialize in-memory cache", e);
            throw new RuntimeException("Failed to initialize any cache implementation", e);
        }
    }
    
    /**
     * Handles the case when Redis is unavailable.
     * 
     * @param config The cache configuration
     * @param logger The logger instance
     * @return A fallback cache or throws an exception
     */
    private static DirtyDataCache handleRedisUnavailable(DirtyCacheConfig config, Logger logger) {
        if (config.shouldFallbackToMemory()) {
            logger.warning("Redis unavailable, falling back to in-memory cache");
            return createMemoryCache(config, logger);
        } else {
            throw new RuntimeException("Redis is unavailable and fallback to memory is disabled");
        }
    }
    
    /**
     * Converts cache configuration Redis settings to RedisConfig.
     * 
     * @param redisSettings The Redis settings from cache configuration
     * @return A RedisConfig instance
     */
    private static RedisConfig convertToRedisConfig(DirtyCacheConfig.RedisSettings redisSettings) {
        return RedisConfig.builder()
                .host(redisSettings.getHost())
                .port(redisSettings.getPort())
                .database(redisSettings.getDatabase())
                .password(redisSettings.getPassword())
                .connectionTimeout(redisSettings.getConnectionTimeout())
                .retryDelay(redisSettings.getRetryDelay())
                .maxRetries(redisSettings.getMaxRetries())
                .maxConnections(redisSettings.getMaxConnections())
                .maxIdleConnections(redisSettings.getMaxIdleConnections())
                .minIdleConnections(redisSettings.getMinIdleConnections())
                .build();
    }
    
    /**
     * Starts a health check task for Redis operations.
     * 
     * @param redisOperations The Redis operations instance
     * @param config The cache configuration
     * @param logger The logger instance
     */
    private static void startHealthCheckTask(JedisRedisOperations redisOperations, 
                                           DirtyCacheConfig config, Logger logger) {
        try {
            // Create a simple health check task
            Thread healthCheckThread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(config.getHealthCheckInterval().toMillis());
                        redisOperations.performHealthCheck();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Health check failed", e);
                    }
                }
            });
            
            healthCheckThread.setDaemon(true);
            healthCheckThread.setName("Redis-HealthCheck");
            healthCheckThread.start();
            
            logger.info("Redis health check task started (interval: " + config.getHealthCheckInterval() + ")");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to start Redis health check task", e);
        }
    }
    
    /**
     * Creates a wrapped cache that provides additional monitoring and resilience.
     *
     * @param baseCache The base cache implementation
     * @param config The cache configuration
     * @param logger The logger instance
     * @return A wrapped cache with monitoring capabilities
     */
    public static DirtyDataCache createMonitoredCache(DirtyDataCache baseCache,
                                                     DirtyCacheConfig config, Logger logger) {
        // For now, just return the base cache
        // In the future, we can add monitoring wrapper here
        return baseCache;
    }
}