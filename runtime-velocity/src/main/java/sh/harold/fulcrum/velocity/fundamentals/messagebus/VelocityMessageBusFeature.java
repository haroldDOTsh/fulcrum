package sh.harold.fulcrum.velocity.fundamentals.messagebus;

import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.velocity.config.ConfigLoader;
import sh.harold.fulcrum.velocity.config.RedisConfig;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;

public class VelocityMessageBusFeature implements VelocityFeature {
    
    private Logger logger;
    private ServiceLocator serviceLocator;
    private MessageBusConfig config;
    private VelocityRedisMessageBus messageBus;
    private VelocityPlayerLocator playerLocator;
    
    @Override
    public String getName() {
        return "MessageBus";
    }
    
    @Override
    public int getPriority() {
        return 100; // High priority as other features depend on it
    }
    
    @Override
    public boolean isFundamental() {
        return true;
    }
    
    @Override
    public String[] getDependencies() {
        return new String[]{"Identity"};
    }
    
    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) throws Exception {
        this.logger = logger;
        this.serviceLocator = serviceLocator;
        
        // Load configuration
        ConfigLoader configLoader = serviceLocator.getRequiredService(ConfigLoader.class);
        this.config = new MessageBusConfig(configLoader.getConfig());
        
        if (!config.isEnabled()) {
            logger.info("MessageBus feature is disabled in configuration");
            return;
        }
        
        ProxyServer server = serviceLocator.getRequiredService(ProxyServer.class);
        
        // Create Redis configuration using builder
        RedisConfig redisConfig = new RedisConfig.Builder()
            .host(config.getRedisHost())
            .port(config.getRedisPort())
            .password(config.getRedisPassword())
            .database(config.getRedisDatabase())
            .timeout(5000)
            .poolSize(10)
            .build();
        
        // Use proxy's bound address as the server ID for consistency
        String serverId = "proxy-" + server.getBoundAddress().getPort();
        this.messageBus = new VelocityRedisMessageBus(serverId, redisConfig);
        
        // Initialize player locator with the message bus
        this.playerLocator = new VelocityPlayerLocator(server, messageBus);
        
        // Register services
        serviceLocator.register(MessageBus.class, messageBus);
        serviceLocator.register(VelocityPlayerLocator.class, playerLocator);
        
        logger.info("MessageBus feature initialized with server ID: {} and Redis at {}:{}",
                   serverId, config.getRedisHost(), config.getRedisPort());
    }
    
    @Override
    public void shutdown() {
        if (messageBus != null) {
            try {
                messageBus.shutdown();
                logger.info("MessageBus disconnected");
            } catch (Exception e) {
                logger.error("Error disconnecting MessageBus", e);
            }
        }
    }
    
    @Override
    public boolean isEnabled() {
        return config == null || config.isEnabled();
    }
}