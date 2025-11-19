package sh.harold.fulcrum.velocity.friends;

import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.friends.FriendService;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.player.PlayerDirectory;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.velocity.config.ConfigLoader;
import sh.harold.fulcrum.velocity.config.RedisConfig;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;
import sh.harold.fulcrum.velocity.session.LettuceSessionRedisClient;

/**
 * Registers the proxy-side friend service so menus and commands can issue mutations.
 */
public final class VelocityFriendFeature implements VelocityFeature {
    private VelocityFriendService friendService;
    private LettuceSessionRedisClient redisClient;
    private ServiceLocator serviceLocator;

    @Override
    public String getName() {
        return "FriendService";
    }

    @Override
    public int getPriority() {
        return 35; // after player data (30) and rank (26) so PlayerDirectory and rank data exist
    }

    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) {
        this.serviceLocator = serviceLocator;
        MessageBus messageBus = serviceLocator.getRequiredService(MessageBus.class);
        ProxyServer proxyServer = serviceLocator.getRequiredService(ProxyServer.class);
        RankService rankService = serviceLocator.getRequiredService(RankService.class);
        PlayerDirectory playerDirectory = serviceLocator.getService(PlayerDirectory.class)
                .orElseThrow(() -> new IllegalStateException("PlayerDirectory not available"));
        ConfigLoader configLoader = serviceLocator.getRequiredService(ConfigLoader.class);
        RedisConfig redisConfig = configLoader.getConfig(RedisConfig.class);
        if (redisConfig == null) {
            throw new IllegalStateException("Redis configuration missing; cannot initialise friend service");
        }
        this.redisClient = new LettuceSessionRedisClient(redisConfig, logger);
        this.friendService = new VelocityFriendService(messageBus, redisClient, proxyServer, rankService, playerDirectory, logger);
        serviceLocator.register(FriendService.class, friendService);
        logger.info("VelocityFriendFeature initialised");
    }

    @Override
    public void shutdown() {
        if (friendService != null) {
            friendService.shutdown();
        }
        if (serviceLocator != null) {
            serviceLocator.unregister(FriendService.class);
        }
        if (redisClient != null) {
            redisClient.close();
        }
    }
}
