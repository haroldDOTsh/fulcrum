package sh.harold.fulcrum.registry.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized Redis manager for the registry. Provides a shared client/connection
 * as well as helpers for loading and executing Lua scripts.
 */
public final class RedisManager implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisManager.class);

    private final RedisConfiguration configuration;
    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final Map<String, RedisScript> scriptCache = new ConcurrentHashMap<>();

    public RedisManager(RedisConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.redisClient = RedisClient.create(buildUri(configuration));
        this.connection = redisClient.connect();
        LOGGER.info("Connected to Redis at {}:{}", configuration.host(), configuration.port());
    }

    private static RedisURI buildUri(RedisConfiguration configuration) {
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(configuration.host())
                .withPort(configuration.port())
                .withDatabase(configuration.database());

        if (configuration.hasPassword()) {
            builder.withPassword(configuration.password().toCharArray());
        }

        return builder.build();
    }

    public RedisConfiguration configuration() {
        return configuration;
    }

    public RedisCommands<String, String> sync() {
        return connection.sync();
    }

    public StatefulRedisConnection<String, String> connection() {
        return connection;
    }

    public RedisScript loadScript(String name, ScriptOutputType outputType, String scriptSource) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(outputType, "outputType");
        Objects.requireNonNull(scriptSource, "scriptSource");

        String sha = sync().scriptLoad(scriptSource);
        RedisScript script = new RedisScript(name, sha, outputType, scriptSource);
        scriptCache.put(name, script);
        return script;
    }

    public RedisScript loadScriptFromResource(String name, ScriptOutputType outputType, String resourcePath) {
        String source = RedisScriptLoader.loadScript(resourcePath);
        return loadScript(name, outputType, source);
    }

    @SuppressWarnings("unchecked")
    public <T> T eval(RedisScript script, List<String> keys, List<String> args) {
        Objects.requireNonNull(script, "script");
        String[] keyArray = (keys == null ? Collections.<String>emptyList() : keys).toArray(String[]::new);
        String[] argArray = (args == null ? Collections.<String>emptyList() : args).toArray(String[]::new);
        Object result = sync().evalsha(script.sha(), script.outputType(), keyArray, argArray);
        return (T) result;
    }

    public RedisScript getCachedScript(String name) {
        return scriptCache.get(name);
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (Exception ex) {
            LOGGER.warn("Failed to close Redis connection cleanly", ex);
        }
        try {
            redisClient.shutdown(Duration.ofSeconds(1), Duration.ofSeconds(5));
        } catch (Exception ex) {
            LOGGER.warn("Failed to shutdown Redis client cleanly", ex);
        }
    }
}
