package sh.harold.fulcrum.registry.redis;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Utility for loading Lua scripts from the classpath.
 */
public final class RedisScriptLoader {

    private RedisScriptLoader() {
    }

    public static String loadScript(String resourcePath) {
        Objects.requireNonNull(resourcePath, "resourcePath");
        InputStream inputStream = RedisScriptLoader.class.getClassLoader().getResourceAsStream(resourcePath);
        if (inputStream == null) {
            throw new IllegalArgumentException("Redis script resource not found: " + resourcePath);
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read Redis script: " + resourcePath, ex);
        }
    }
}
