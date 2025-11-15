package sh.harold.fulcrum.registry.redis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.net.ServerSocket;

public abstract class RedisIntegrationTestSupport {

    private static RedisServer redisServer;
    private static int redisPort;

    @BeforeAll
    static void startRedis() {
        redisPort = findAvailablePort();
        redisServer = RedisServer.builder()
                .port(redisPort)
                .setting("maxmemory 64mb")
                .build();

        redisServer.start();
    }

    @AfterAll
    static void stopRedis() {
        if (redisServer != null) {
            redisServer.stop();
        }
    }

    private static int findAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to find an available port for embedded Redis", ex);
        }
    }

    protected RedisConfiguration redisConfiguration() {
        return new RedisConfiguration("127.0.0.1", redisPort, "", 0);
    }

    protected RedisManager newRedisManager() {
        return new RedisManager(redisConfiguration());
    }
}
