package sh.harold.fulcrum.velocity.session;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import sh.harold.fulcrum.velocity.config.RedisConfig;

/**
 * Minimal Redis helper for the Velocity proxy, mirroring the Paper implementation.
 */
public class LettuceSessionRedisClient implements AutoCloseable {

    private final Logger logger;
    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final boolean available;

    public LettuceSessionRedisClient(RedisConfig config, Logger logger) {
        this.logger = logger;

        try {
            RedisURI.Builder builder = RedisURI.builder()
                    .withHost(config.getHost())
                    .withPort(config.getPort())
                    .withDatabase(config.getDatabase())
                    .withTimeout(config.getConnectionTimeout());

            if (config.getPassword() != null && !config.getPassword().isBlank()) {
                builder.withPassword(config.getPassword().toCharArray());
            }

            RedisURI uri = builder.build();
            this.redisClient = RedisClient.create(uri);
            this.connection = redisClient.connect();
            this.available = testConnection();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to establish Redis connection: " + ex.getMessage(), ex);
        }
    }

    private boolean testConnection() {
        try {
            RedisCommands<String, String> commands = connection.sync();
            String pong = commands.ping();
            boolean ok = "PONG".equalsIgnoreCase(pong);
            if (!ok) {
                logger.warn("Unexpected Redis PING response: {}", pong);
            }
            return ok;
        } catch (Exception e) {
            logger.error("Redis connection test failed", e);
            return false;
        }
    }

    public boolean isAvailable() {
        return available && connection != null && connection.isOpen();
    }

    public void set(String key, String value, long ttlSeconds) {
        if (!isAvailable()) {
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
            logger.error("Failed to set key {} in Redis", key, e);
        }
    }

    public String get(String key) {
        if (!isAvailable()) {
            return null;
        }
        try {
            return connection.sync().get(key);
        } catch (Exception e) {
            logger.error("Failed to get key {} from Redis", key, e);
            return null;
        }
    }

    public void delete(String key) {
        if (!isAvailable()) {
            return;
        }
        try {
            connection.sync().del(key);
        } catch (Exception e) {
            logger.error("Failed to delete key {} from Redis", key, e);
        }
    }

    public java.util.Set<String> smembers(String key) {
        if (!isAvailable()) {
            return java.util.Set.of();
        }
        try {
            return connection.sync().smembers(key);
        } catch (Exception e) {
            logger.error("Failed to read set {} from Redis", key, e);
            return java.util.Set.of();
        }
    }

    @Override
    public void close() {
        if (connection != null) {
            connection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }
}
