package sh.harold.fulcrum.registry.environment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.environment.directory.EnvironmentDirectoryView;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;

public final class EnvironmentDirectoryCache implements Closeable {
    private static final String DIRECTORY_KEY = "network:environments:directory";
    private static final String META_KEY = "network:environments:meta";

    private final Logger logger;
    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final ObjectMapper mapper;
    private final boolean available;

    public EnvironmentDirectoryCache(String host, int port, String password, Logger logger) {
        this.logger = logger;

        RedisURI.Builder builder = RedisURI.builder()
                .withHost(host)
                .withPort(port);
        if (password != null && !password.isBlank()) {
            builder.withPassword(password.toCharArray());
        }

        RedisURI uri = builder.build();
        this.redisClient = RedisClient.create(uri);
        this.connection = redisClient.connect();
        this.available = testConnection();

        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    boolean isAvailable() {
        return available && connection.isOpen();
    }

    void store(EnvironmentDirectoryView view) {
        Objects.requireNonNull(view, "view");
        if (!isAvailable()) {
            throw new IllegalStateException("Redis connection unavailable for environment directory cache");
        }

        try {
            RedisCommands<String, String> commands = connection.sync();
            String payload = mapper.writeValueAsString(view);
            commands.set(DIRECTORY_KEY, payload);
            if (view.revision() != null && !view.revision().isBlank()) {
                commands.set(META_KEY, view.revision());
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to write environment directory to Redis", ex);
        }
    }

    @Override
    public void close() throws IOException {
        if (connection != null) {
            connection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    private boolean testConnection() {
        try {
            String response = connection.sync().ping();
            if (!"PONG".equalsIgnoreCase(response)) {
                logger.warn("Unexpected Redis response while testing environment directory cache: {}", response);
            }
            return true;
        } catch (Exception ex) {
            logger.error("Failed to ping Redis for environment directory cache", ex);
            return false;
        }
    }
}
