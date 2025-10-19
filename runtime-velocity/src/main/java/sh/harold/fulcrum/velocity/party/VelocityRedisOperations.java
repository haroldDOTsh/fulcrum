package sh.harold.fulcrum.velocity.party;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import sh.harold.fulcrum.velocity.config.RedisConfig;

import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

final class VelocityRedisOperations implements AutoCloseable {
    private final Logger logger;
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final boolean available;

    VelocityRedisOperations(RedisConfig config, Logger logger) {
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
            this.client = RedisClient.create(uri);
            this.connection = client.connect();
            this.available = testConnection();
        } catch (Exception ex) {
            logger.error("Failed to initialise Redis connection", ex);
            throw new IllegalStateException("Unable to initialise Redis", ex);
        }
    }

    private boolean testConnection() {
        try {
            RedisCommands<String, String> commands = connection.sync();
            return "PONG".equalsIgnoreCase(commands.ping());
        } catch (Exception ex) {
            logger.error("Redis ping failed", ex);
            return false;
        }
    }

    boolean isAvailable() {
        return available && connection != null && connection.isOpen();
    }

    void set(String key, String value, long ttlSeconds) {
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
        } catch (Exception ex) {
            logger.warn("Failed to set key {} in Redis", key, ex);
        }
    }

    boolean setIfAbsent(String key, String value, long ttlSeconds) {
        if (!isAvailable()) {
            return false;
        }
        try {
            RedisCommands<String, String> commands = connection.sync();
            SetArgs args = SetArgs.Builder.nx();
            if (ttlSeconds > 0) {
                args.ex(ttlSeconds);
            }
            String response = commands.set(key, value, args);
            return response != null && response.equalsIgnoreCase("OK");
        } catch (Exception ex) {
            logger.warn("Failed to set NX key {} in Redis", key, ex);
            return false;
        }
    }

    String get(String key) {
        if (!isAvailable()) {
            return null;
        }
        try {
            return connection.sync().get(key);
        } catch (Exception ex) {
            logger.warn("Failed to get key {} from Redis", key, ex);
            return null;
        }
    }

    boolean delete(String key) {
        if (!isAvailable()) {
            return false;
        }
        try {
            return connection.sync().del(key) > 0;
        } catch (Exception ex) {
            logger.warn("Failed to delete key {} from Redis", key, ex);
            return false;
        }
    }

    long delete(String... keys) {
        if (!isAvailable() || keys == null || keys.length == 0) {
            return 0;
        }
        try {
            return connection.sync().del(keys);
        } catch (Exception ex) {
            logger.warn("Failed to delete keys from Redis", ex);
            return 0;
        }
    }

    boolean deleteIfMatches(String key, String value) {
        if (!isAvailable()) {
            return false;
        }
        try {
            Long result = connection.sync().eval(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                    ScriptOutputType.INTEGER,
                    new String[]{key},
                    value
            );
            return result != null && result > 0;
        } catch (Exception ex) {
            logger.warn("Failed to delete key {} with comparison", key, ex);
            return false;
        }
    }

    void sAdd(String setKey, String member) {
        if (!isAvailable()) {
            return;
        }
        try {
            connection.sync().sadd(setKey, member);
        } catch (Exception ex) {
            logger.warn("Failed to add member {} to set {}", member, setKey, ex);
        }
    }

    void sRem(String setKey, String member) {
        if (!isAvailable()) {
            return;
        }
        try {
            connection.sync().srem(setKey, member);
        } catch (Exception ex) {
            logger.warn("Failed to remove member {} from set {}", member, setKey, ex);
        }
    }

    Set<String> sMembers(String setKey) {
        if (!isAvailable()) {
            return Collections.emptySet();
        }
        try {
            return connection.sync().smembers(setKey);
        } catch (Exception ex) {
            logger.warn("Failed to read members of set {}", setKey, ex);
            return Collections.emptySet();
        }
    }

    Set<String> keys(String pattern) {
        if (!isAvailable()) {
            return Collections.emptySet();
        }
        try {
            return connection.sync().keys(pattern).stream().collect(Collectors.toSet());
        } catch (Exception ex) {
            logger.warn("Failed to enumerate keys for pattern {}", pattern, ex);
            return Collections.emptySet();
        }
    }

    @Override
    public void close() {
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }
}
