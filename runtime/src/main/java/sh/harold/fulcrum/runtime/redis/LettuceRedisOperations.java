package sh.harold.fulcrum.runtime.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.Collections;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Redis operations implementation using Lettuce client.
 * Provides a consistent interface for Redis operations matching the proxy implementation.
 */
public class LettuceRedisOperations implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(LettuceRedisOperations.class.getName());

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisConfig config;
    private volatile boolean available = false;

    /**
     * Creates a new Lettuce Redis operations instance.
     *
     * @param config Redis configuration
     */
    public LettuceRedisOperations(RedisConfig config) {
        this.config = config;

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

            // Create Redis client and connection
            this.redisClient = RedisClient.create(redisUri);
            this.connection = redisClient.connect();

            // Test connection
            this.available = testConnection();

            if (available) {
                LOGGER.info("Lettuce Redis connection established successfully");
            } else {
                LOGGER.warning("Lettuce Redis connection failed - operating in degraded mode");
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize Lettuce Redis connection", e);
            throw new RuntimeException("Failed to initialize Redis connection", e);
        }
    }

    /**
     * Tests the Redis connection.
     *
     * @return true if connection is working
     */
    private boolean testConnection() {
        try {
            RedisCommands<String, String> commands = connection.sync();
            String pong = commands.ping();
            return "PONG".equals(pong);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Redis connection test failed", e);
            return false;
        }
    }

    public void set(String key, String value, long ttlSeconds) {
        if (!available) {
            LOGGER.warning("Redis not available for SET operation");
            return;
        }

        try {
            RedisCommands<String, String> commands = connection.sync();
            if (ttlSeconds > 0) {
                commands.setex(key, ttlSeconds, value);
            } else {
                commands.set(key, value);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to set key: " + key, e);
        }
    }

    public String get(String key) {
        if (!available) {
            return null;
        }

        try {
            RedisCommands<String, String> commands = connection.sync();
            return commands.get(key);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get key: " + key, e);
            return null;
        }
    }

    public boolean delete(String key) {
        if (!available) {
            return false;
        }

        try {
            RedisCommands<String, String> commands = connection.sync();
            return commands.del(key) > 0;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to delete key: " + key, e);
            return false;
        }
    }

    public void sAdd(String setKey, String member) {
        if (!available) {
            return;
        }

        try {
            RedisCommands<String, String> commands = connection.sync();
            commands.sadd(setKey, member);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to add member to set: " + setKey, e);
        }
    }

    public void sRem(String setKey, String member) {
        if (!available) {
            return;
        }

        try {
            RedisCommands<String, String> commands = connection.sync();
            commands.srem(setKey, member);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to remove member from set: " + setKey, e);
        }
    }

    public Set<String> sMembers(String setKey) {
        if (!available) {
            return Collections.emptySet();
        }

        try {
            RedisCommands<String, String> commands = connection.sync();
            return commands.smembers(setKey);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get members of set: " + setKey, e);
            return Collections.emptySet();
        }
    }

    public boolean sIsMember(String setKey, String member) {
        if (!available) {
            return false;
        }

        try {
            RedisCommands<String, String> commands = connection.sync();
            return commands.sismember(setKey, member);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to check member in set: " + setKey, e);
            return false;
        }
    }

    public Set<String> keys(String pattern) {
        if (!available) {
            return Collections.emptySet();
        }

        try {
            RedisCommands<String, String> commands = connection.sync();
            return commands.keys(pattern).stream().collect(Collectors.toSet());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get keys with pattern: " + pattern, e);
            return Collections.emptySet();
        }
    }

    public long delete(String... keys) {
        if (!available || keys == null || keys.length == 0) {
            return 0;
        }

        try {
            RedisCommands<String, String> commands = connection.sync();
            return commands.del(keys);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to delete multiple keys", e);
            return 0;
        }
    }

    public boolean isAvailable() {
        return available && connection != null && connection.isOpen();
    }

    /**
     * Performs a health check and updates availability status.
     */
    public void performHealthCheck() {
        try {
            RedisCommands<String, String> commands = connection.sync();
            String pong = commands.ping();
            boolean wasAvailable = available;
            available = "PONG".equals(pong);

            if (!wasAvailable && available) {
                LOGGER.info("Redis connection restored");
            } else if (wasAvailable && !available) {
                LOGGER.warning("Redis connection lost");
            }
        } catch (Exception e) {
            if (available) {
                LOGGER.log(Level.WARNING, "Redis health check failed", e);
                available = false;
            }
        }
    }

    /**
     * Closes the Redis connection and releases resources.
     */
    @Override
    public void close() {
        if (connection != null && connection.isOpen()) {
            connection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
        LOGGER.info("Lettuce Redis connection closed");
    }

    /**
     * Gets connection statistics for monitoring.
     *
     * @return Connection status as a string
     */
    public String getConnectionStatus() {
        if (connection == null) {
            return "Not initialized";
        }

        return String.format(
                "Connected: %s, Available: %s",
                connection.isOpen(),
                available
        );
    }
}