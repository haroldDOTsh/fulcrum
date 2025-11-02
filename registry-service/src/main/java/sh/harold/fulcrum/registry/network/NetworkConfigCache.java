package sh.harold.fulcrum.registry.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.network.NetworkProfileView;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;

public final class NetworkConfigCache implements Closeable {
    private static final String ACTIVE_KEY = "network:config:active";

    private final Logger logger;
    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final ObjectMapper mapper;
    private final boolean available;

    public NetworkConfigCache(String host, int port, String password, Logger logger) {
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

    void storeProfile(NetworkProfileView profile) {
        Objects.requireNonNull(profile, "profile");
        if (!isAvailable()) {
            throw new IllegalStateException("Redis connection unavailable for network config cache");
        }

        try {
            RedisCommands<String, String> commands = connection.sync();
            String profileJson = mapper.writeValueAsString(profile);
            commands.set(ACTIVE_KEY, profileJson);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to write network config to Redis", ex);
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
                logger.warn("Unexpected Redis response while testing network cache: {}", response);
            }
            return true;
        } catch (Exception ex) {
            logger.error("Failed to ping Redis for network config cache", ex);
            return false;
        }
    }
}
