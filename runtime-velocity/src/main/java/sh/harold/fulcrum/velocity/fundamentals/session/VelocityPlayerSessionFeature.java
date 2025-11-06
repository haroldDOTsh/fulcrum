package sh.harold.fulcrum.velocity.fundamentals.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.config.ConfigLoader;
import sh.harold.fulcrum.velocity.config.RedisConfig;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;
import sh.harold.fulcrum.velocity.session.LettuceSessionRedisClient;
import sh.harold.fulcrum.velocity.session.VelocityPlayerSessionService;

import java.util.Map;
import java.util.UUID;

/**
 * Initializes the proxy-side player session cache.
 */
public class VelocityPlayerSessionFeature implements VelocityFeature {

    private LettuceSessionRedisClient redisClient;
    private VelocityPlayerSessionService sessionService;
    private ProxyServer proxy;
    private FulcrumVelocityPlugin plugin;
    private ProxySessionListener sessionListener;

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
        proxy = serviceLocator.getRequiredService(ProxyServer.class);
        plugin = serviceLocator.getRequiredService(FulcrumVelocityPlugin.class);
        sessionListener = new ProxySessionListener(sessionService, logger);
        proxy.getEventManager().register(plugin, sessionListener);

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
        if (proxy != null && plugin != null && sessionListener != null) {
            proxy.getEventManager().unregisterListener(plugin, sessionListener);
        }
    }

    private static final class ProxySessionListener {
        private final VelocityPlayerSessionService sessions;
        private final Logger logger;
        private final java.util.concurrent.ConcurrentMap<UUID, String> sessionIds = new java.util.concurrent.ConcurrentHashMap<>();

        private ProxySessionListener(VelocityPlayerSessionService sessions, Logger logger) {
            this.sessions = sessions;
            this.logger = logger;
        }

        @Subscribe
        public void onPostLogin(PostLoginEvent event) {
            try {
                VelocityPlayerSessionService.PlayerSessionHandle handle = sessions.attachOrCreateSession(
                        event.getPlayer().getUniqueId(),
                        Map.of("username", event.getPlayer().getUsername())
                );
                sessionIds.put(event.getPlayer().getUniqueId(), handle.sessionId());
            } catch (Exception ex) {
                logger.warn("Failed to attach session for {}", event.getPlayer().getUsername(), ex);
            }
        }

        @Subscribe
        public void onDisconnect(DisconnectEvent event) {
            UUID playerId = event.getPlayer().getUniqueId();
            String sessionId = sessionIds.remove(playerId);
            if (sessionId != null) {
                sessions.endSession(playerId, sessionId);
            }
        }
    }
}
