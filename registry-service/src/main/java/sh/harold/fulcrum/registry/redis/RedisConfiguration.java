package sh.harold.fulcrum.registry.redis;

import java.util.Objects;

/**
 * Simple value object describing how the registry connects to Redis.
 */
public record RedisConfiguration(String host, int port, String password) {

    public RedisConfiguration {
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new IllegalArgumentException("Redis host must not be blank");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Redis port must be between 1 and 65535");
        }
        password = password == null ? "" : password;
    }

    public boolean hasPassword() {
        return password != null && !password.isBlank();
    }
}
