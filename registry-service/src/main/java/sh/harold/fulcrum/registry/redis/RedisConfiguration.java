package sh.harold.fulcrum.registry.redis;

import java.util.Objects;

/**
 * Simple value object describing how the registry connects to Redis.
 */
public record RedisConfiguration(String host, int port, String password, int database) {

    public RedisConfiguration {
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new IllegalArgumentException("Redis host must not be blank");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Redis port must be between 1 and 65535");
        }
        password = password == null ? "" : password;
        if (database < 0 || database > 15) {
            throw new IllegalArgumentException("Redis database must be between 0 and 15");
        }
    }

    public boolean hasPassword() {
        return password != null && !password.isBlank();
    }
}
