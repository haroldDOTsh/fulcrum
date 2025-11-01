package sh.harold.fulcrum.registry.redis;

import io.lettuce.core.ScriptOutputType;

import java.util.Objects;

/**
 * Represents a Lua script loaded into Redis.
 */
public record RedisScript(String name, String sha, ScriptOutputType outputType, String source) {

    public RedisScript {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(sha, "sha");
        Objects.requireNonNull(outputType, "outputType");
        Objects.requireNonNull(source, "source");
    }
}
