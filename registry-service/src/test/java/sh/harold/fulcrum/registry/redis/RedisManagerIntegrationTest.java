package sh.harold.fulcrum.registry.redis;

import io.lettuce.core.ScriptOutputType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class RedisManagerIntegrationTest extends RedisIntegrationTestSupport {

    @Test
    @DisplayName("Redis manager stores and retrieves plain values")
    void shouldRoundTripSimpleValue() {
        try (RedisManager manager = newRedisManager()) {
            manager.sync().set("fulcrum:test:key", "value");

            String result = manager.sync().get("fulcrum:test:key");

            assertThat(result).isEqualTo("value");
        }
    }

    @Test
    @DisplayName("Redis manager loads and executes Lua scripts")
    void shouldExecuteLoadedScript() {
        try (RedisManager manager = newRedisManager()) {
            RedisScript script = manager.loadScript("ping", ScriptOutputType.STATUS, "return redis.call('ping')");

            String result = manager.eval(script, Collections.emptyList(), Collections.emptyList());

            assertThat(result).isEqualTo("PONG");
        }
    }
}
