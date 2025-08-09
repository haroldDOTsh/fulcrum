package sh.harold.fulcrum.fundamentals.messagebus;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.PlayerLocator;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.runtime.redis.RedisConfig;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Feature that provides message bus functionality for inter-server communication.
 */
public class MessageBusFeature implements PluginFeature {
    private static final Logger LOGGER = Logger.getLogger(MessageBusFeature.class.getName());
    
    private JavaPlugin plugin;
    private DependencyContainer container;
    private MessageBus messageBus;
    private MessageBusConfig config;
    private PlayerLocator playerLocator;
    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> redisConnection;
    
    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        this.container = container;
        
        // Load configuration
        plugin.saveDefaultConfig();
        config = new MessageBusConfig(plugin.getConfig().getConfigurationSection("message-bus"));
        
        // Try to initialize Redis message bus
        boolean redisEnabled = plugin.getConfig().getBoolean("redis.enabled", false);
        boolean forceSimpleMode = plugin.getConfig().getBoolean("message-bus.force-simple-mode", false);
        
        if (!forceSimpleMode && redisEnabled) {
            if (initializeRedisMessageBus()) {
                LOGGER.info("Redis message bus initialized successfully");
            } else {
                LOGGER.warning("Failed to initialize Redis message bus, falling back to simple mode");
                initializeSimpleMessageBus();
            }
        } else {
            if (forceSimpleMode) {
                LOGGER.info("Simple message bus mode forced by configuration");
            } else {
                LOGGER.info("Redis not enabled, using simple message bus");
            }
            initializeSimpleMessageBus();
        }
        
        // Register services
        container.register(MessageBus.class, messageBus);
        container.register(MessageBusConfig.class, config);
        container.register(PlayerLocator.class, playerLocator);
        
        LOGGER.info("Message bus feature initialized with server ID: " + config.getServerId());
    }
    
    private boolean initializeRedisMessageBus() {
        try {
            // Load Redis configuration
            ConfigurationSection redisSection = plugin.getConfig().getConfigurationSection("redis");
            if (redisSection == null) {
                LOGGER.warning("Redis configuration section not found");
                return false;
            }
            
            RedisConfig redisConfig = RedisConfig.builder()
                .host(redisSection.getString("host", "localhost"))
                .port(redisSection.getInt("port", 6379))
                .database(redisSection.getInt("database", 0))
                .password(redisSection.getString("password"))
                .maxConnections(redisSection.getInt("pool.max-connections", 20))
                .maxIdleConnections(redisSection.getInt("pool.max-idle", 10))
                .minIdleConnections(redisSection.getInt("pool.min-idle", 5))
                .build();
            
            // Create Redis message bus
            RedisMessageBus redisMessageBus = new RedisMessageBus(config.getServerId(), redisConfig);
            
            // Test connection
            if (!redisMessageBus.isConnected()) {
                redisMessageBus.shutdown();
                return false;
            }
            
            this.messageBus = redisMessageBus;
            
            // Create Redis-backed player locator
            // We need to share the connection from RedisMessageBus
            // For now, create a separate connection (could be optimized later)
            this.redisClient = RedisClient.create("redis://" + redisConfig.getHost() + ":" + redisConfig.getPort());
            this.redisConnection = redisClient.connect();
            
            this.playerLocator = new RedisPlayerLocator(
                messageBus,
                redisConnection,
                config.getServerId(),
                config.getProxyId()
            );
            
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize Redis message bus", e);
            return false;
        }
    }
    
    private void initializeSimpleMessageBus() {
        SimpleMessageBus simpleMessageBus = new SimpleMessageBus(config.getServerId());
        this.messageBus = simpleMessageBus;
        this.playerLocator = new PlayerLocator(messageBus);
    }
    
    @Override
    public void shutdown() {
        // Shutdown message bus
        if (messageBus instanceof RedisMessageBus) {
            ((RedisMessageBus) messageBus).shutdown();
        } else if (messageBus instanceof SimpleMessageBus) {
            ((SimpleMessageBus) messageBus).shutdown();
        }
        
        // Close Redis connections if they exist
        if (redisConnection != null && redisConnection.isOpen()) {
            redisConnection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
        
        LOGGER.info("Message bus feature shut down");
    }
    
    @Override
    public int getPriority() {
        // Initialize after core features but before application features
        return 60;
    }
}