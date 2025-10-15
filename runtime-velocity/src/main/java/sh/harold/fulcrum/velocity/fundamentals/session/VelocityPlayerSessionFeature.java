package sh.harold.fulcrum.velocity.fundamentals.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import sh.harold.fulcrum.velocity.config.ConfigLoader;
import sh.harold.fulcrum.velocity.config.RedisConfig;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;
import sh.harold.fulcrum.velocity.session.LettuceSessionRedisClient;
import sh.harold.fulcrum.velocity.session.VelocityPlayerSessionService;

/**
 * Initializes the proxy-side player session cache.
 */
public class VelocityPlayerSessionFeature implements VelocityFeature {

    private LettuceSessionRedisClient redisClient;
    private VelocityPlayerSessionService sessionService;

    @Override
    public String getName() {
        return "PlayerSession";
    }

    @Override
    public int getPriority() {
        return 25; // Before data features that depend on live session data
    }

    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) {
        ConfigLoader configLoader = serviceLocator.getRequiredService(ConfigLoader.class);
        RedisConfig redisConfig = configLoader.getConfig(RedisConfig.class);
        if (redisConfig == null) {
            throw new IllegalStateException("Redis configuration missing; cannot initialize session service");
        }

        redisClient = new LettuceSessionRedisClient(redisConfig, logger);
        String proxyId = configLoader.get("proxy-id", "velocity-proxy");
        sessionService = new VelocityPlayerSessionService(redisClient, new ObjectMapper(), logger, proxyId);

        serviceLocator.register(VelocityPlayerSessionService.class, sessionService);
    }

    @Override
    public void shutdown() {
        if (redisClient != null) {
            try {
                redisClient.close();
            } catch (Exception e) {
                // ignore
            }
        }
    }
}
