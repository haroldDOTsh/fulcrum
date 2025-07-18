package sh.harold.fulcrum.runtime.redis;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisException;
import sh.harold.fulcrum.api.data.dirty.RedisDirtyDataCache;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Production-ready Redis operations implementation using Jedis client.
 * <p>
 * This implementation provides:
 * - Connection pooling for optimal performance
 * - Automatic failover and retry logic
 * - Comprehensive error handling and logging
 * - Thread-safe operations
 * - Graceful degradation when Redis is unavailable
 */
public class JedisRedisOperations implements RedisDirtyDataCache.RedisOperations {

    private static final Logger LOGGER = Logger.getLogger(JedisRedisOperations.class.getName());

    private final JedisPool jedisPool;
    private final RedisConfig config;
    private volatile boolean available = false;

    /**
     * Creates a new Jedis Redis operations instance.
     *
     * @param config Redis configuration
     */
    public JedisRedisOperations(RedisConfig config) {
        this.config = config;
        this.jedisPool = createJedisPool();
        this.available = testConnection();

        if (available) {
            LOGGER.info("Redis connection established successfully");
        } else {
            LOGGER.warning("Redis connection failed - operating in degraded mode");
        }
    }

    /**
     * Creates and configures the Jedis connection pool.
     *
     * @return Configured JedisPool instance
     */
    private JedisPool createJedisPool() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();

        // Pool configuration for optimal performance
        poolConfig.setMaxTotal(config.getMaxConnections());
        poolConfig.setMaxIdle(config.getMaxIdleConnections());
        poolConfig.setMinIdle(config.getMinIdleConnections());
        poolConfig.setMaxWaitMillis(config.getConnectionTimeout().toMillis());
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(true);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setMinEvictableIdleTimeMillis(Duration.ofMinutes(1).toMillis());
        poolConfig.setTimeBetweenEvictionRunsMillis(Duration.ofSeconds(30).toMillis());
        poolConfig.setNumTestsPerEvictionRun(3);
        poolConfig.setBlockWhenExhausted(true);

        try {
            if (config.getPassword() != null && !config.getPassword().isEmpty()) {
                return new JedisPool(poolConfig, config.getHost(), config.getPort(),
                        (int) config.getConnectionTimeout().toMillis(),
                        config.getPassword(), config.getDatabase());
            } else {
                return new JedisPool(poolConfig, config.getHost(), config.getPort(),
                        (int) config.getConnectionTimeout().toMillis(),
                        null, config.getDatabase());
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to create Redis connection pool", e);
            throw new RuntimeException("Failed to initialize Redis connection pool", e);
        }
    }

    /**
     * Tests the Redis connection.
     *
     * @return true if connection is working
     */
    private boolean testConnection() {
        try (Jedis jedis = jedisPool.getResource()) {
            String response = jedis.ping();
            return "PONG".equalsIgnoreCase(response);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Redis connection test failed", e);
            return false;
        }
    }

    /**
     * Executes a Redis operation with automatic retry and error handling.
     *
     * @param operation The operation to execute
     * @param <T>       Return type
     * @return Operation result, or null if failed
     */
    private <T> T executeWithRetry(RedisOperation<T> operation) {
        int attempts = 0;
        Exception lastException = null;

        while (attempts < config.getMaxRetries()) {
            try (Jedis jedis = jedisPool.getResource()) {
                T result = operation.execute(jedis);

                // Mark as available on successful operation
                if (!available) {
                    available = true;
                    LOGGER.info("Redis connection restored");
                }

                return result;
            } catch (JedisException e) {
                lastException = e;
                attempts++;

                if (attempts < config.getMaxRetries()) {
                    try {
                        Thread.sleep(config.getRetryDelay().toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        // Mark as unavailable after repeated failures
        if (available) {
            available = false;
            LOGGER.log(Level.WARNING, "Redis marked as unavailable after " + attempts + " failed attempts", lastException);
        }

        return null;
    }

    @Override
    public void set(String key, String value, long ttlSeconds) {
        executeWithRetry(jedis -> {
            if (ttlSeconds > 0) {
                jedis.setex(key, ttlSeconds, value);
            } else {
                jedis.set(key, value);
            }
            return null;
        });
    }

    @Override
    public String get(String key) {
        return executeWithRetry(jedis -> jedis.get(key));
    }

    @Override
    public boolean delete(String key) {
        Long result = executeWithRetry(jedis -> jedis.del(key));
        return result != null && result > 0;
    }

    @Override
    public void sAdd(String setKey, String member) {
        executeWithRetry(jedis -> {
            jedis.sadd(setKey, member);
            return null;
        });
    }

    @Override
    public void sRem(String setKey, String member) {
        executeWithRetry(jedis -> {
            jedis.srem(setKey, member);
            return null;
        });
    }

    @Override
    public Set<String> sMembers(String setKey) {
        Set<String> result = executeWithRetry(jedis -> jedis.smembers(setKey));
        return result != null ? result : Collections.emptySet();
    }

    @Override
    public boolean sIsMember(String setKey, String member) {
        Boolean result = executeWithRetry(jedis -> jedis.sismember(setKey, member));
        return result != null && result;
    }

    @Override
    public Set<String> keys(String pattern) {
        Set<String> result = executeWithRetry(jedis -> jedis.keys(pattern));
        return result != null ? result : Collections.emptySet();
    }

    @Override
    public long delete(String... keys) {
        if (keys.length == 0) {
            return 0;
        }

        Long result = executeWithRetry(jedis -> jedis.del(keys));
        return result != null ? result : 0;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    /**
     * Performs a health check and updates availability status.
     * This method should be called periodically to maintain accurate availability status.
     */
    public void performHealthCheck() {
        boolean wasAvailable = available;
        available = testConnection();

        if (!wasAvailable && available) {
            LOGGER.info("Redis health check: Connection restored");
        } else if (wasAvailable && !available) {
            LOGGER.warning("Redis health check: Connection lost");
        }
    }

    /**
     * Closes the Redis connection pool and releases resources.
     */
    public void close() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
            LOGGER.info("Redis connection pool closed");
        }
    }

    /**
     * Gets current pool statistics for monitoring.
     *
     * @return Pool statistics as a formatted string
     */
    public String getPoolStats() {
        if (jedisPool == null) {
            return "Pool not initialized";
        }

        return String.format(
                "Active: %d, Idle: %d, Closed: %s",
                jedisPool.getNumActive(),
                jedisPool.getNumIdle(),
                jedisPool.isClosed()
        );
    }

    /**
     * Functional interface for Redis operations with automatic resource management.
     *
     * @param <T> Return type
     */
    @FunctionalInterface
    private interface RedisOperation<T> {
        T execute(Jedis jedis) throws JedisException;
    }
}