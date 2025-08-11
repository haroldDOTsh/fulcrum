package sh.harold.fulcrum.fundamentals.messagebus;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.PlayerLocator;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.runtime.redis.JedisRedisOperations;
import sh.harold.fulcrum.runtime.redis.RedisConfig;

import java.io.File;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Feature that provides message bus functionality for inter-server communication.
 * Also manages Redis connections and RedisServerRegistry when Redis is enabled.
 * This feature depends on ServerLifecycleFeature for ServerIdentifier.
 */
public class MessageBusFeature implements PluginFeature {
    private static final Logger LOGGER = Logger.getLogger(MessageBusFeature.class.getName());
    
    private MessageBus messageBus;
    private PlayerLocator playerLocator;
    private JedisRedisOperations redisOperations;
    
    @Override
    public int getPriority() {
        // Priority 10 - Infrastructure layer, loads before services
        return 10;
    }
    
    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        // Use temporary UUID for initial node identification
        // This will be updated later by ServerLifecycleFeature after registration
        String tempNodeId = "temp-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        
        // Load database configuration
        File databaseConfigFile = new File(plugin.getDataFolder(), "database-config.yml");
        if (!databaseConfigFile.exists()) {
            plugin.saveResource("database-config.yml", false);
            databaseConfigFile = new File(plugin.getDataFolder(), "database-config.yml");
        }
        FileConfiguration databaseConfig = YamlConfiguration.loadConfiguration(databaseConfigFile);
        
        // Check if Redis is enabled
        ConfigurationSection redisSection = databaseConfig.getConfigurationSection("redis");
        boolean redisEnabled = redisSection != null && redisSection.getBoolean("enabled", false);
        
        if (redisEnabled) {
            try {
                LOGGER.info("Initializing Redis connection for MessageBus...");
                
                // Create Redis configuration
                RedisConfig.Builder configBuilder = RedisConfig.builder()
                    .host(redisSection.getString("host", "localhost"))
                    .port(redisSection.getInt("port", 6379))
                    .password(redisSection.getString("password", ""))
                    .database(redisSection.getInt("database", 0))
                    .connectionTimeout(Duration.ofMillis(redisSection.getInt("connection-timeout", 2000)))
                    .maxConnections(redisSection.getInt("pool.max-total", 128))
                    .maxIdleConnections(redisSection.getInt("pool.max-idle", 64))
                    .minIdleConnections(redisSection.getInt("pool.min-idle", 16));
                
                RedisConfig redisConfig = configBuilder.build();
                
                // Initialize Redis operations
                this.redisOperations = new JedisRedisOperations(redisConfig);
                
                if (redisOperations.isAvailable()) {
                    // Register Redis operations for other features to use
                    container.register(JedisRedisOperations.class, redisOperations);
                    
                    // Initialize Redis message bus first
                    LOGGER.info("Using Redis message bus for cross-server communication");
                    this.messageBus = new RedisMessageBus(tempNodeId, redisConfig);
                    
                    // For now, use SimplePlayerLocator as RedisPlayerLocator expects different Redis client
                    // TODO: Implement proper Redis-based player locator with JedisRedisOperations
                    this.playerLocator = new SimplePlayerLocator(messageBus);
                    
                    LOGGER.info("Redis connection established successfully for MessageBus");
                } else {
                    LOGGER.warning("Redis connection failed - falling back to simple message bus");
                    this.messageBus = new SimpleMessageBusWithLogging(tempNodeId);
                    this.playerLocator = new SimplePlayerLocator(messageBus);
                }
            } catch (Exception e) {
                LOGGER.warning("Failed to initialize Redis message bus: " + e.getMessage());
                LOGGER.warning("Falling back to simple message bus (single-server mode)");
                this.messageBus = new SimpleMessageBusWithLogging(tempNodeId);
                this.playerLocator = new SimplePlayerLocator(messageBus);
            }
        } else {
            LOGGER.info("Redis is disabled, using simple message bus (single-server mode)");
            this.messageBus = new SimpleMessageBusWithLogging(tempNodeId);
            this.playerLocator = new SimplePlayerLocator(messageBus);
        }
        
        // Register services
        container.register(MessageBus.class, messageBus);
        container.register(PlayerLocator.class, playerLocator);
        
        // Register debug command
        // TODO: Implement MessageBusDebugCommand for debugging message bus functionality
        // MessageBusDebugCommand.register(plugin, container);
        
        LOGGER.info("Message bus initialized with temporary ID: " + tempNodeId);
    }
    
    @Override
    public void shutdown() {
        if (messageBus != null) {
            if (messageBus instanceof RedisMessageBus) {
                ((RedisMessageBus) messageBus).shutdown();
            } else if (messageBus instanceof SimpleMessageBus) {
                ((SimpleMessageBus) messageBus).shutdown();
            }
        }
        
        if (redisOperations != null) {
            try {
                redisOperations.close();
                LOGGER.info("Redis connection closed");
            } catch (Exception e) {
                LOGGER.warning("Error closing Redis connection: " + e.getMessage());
            }
        }
    }
}

/**
 * Extended SimpleMessageBus that logs when cross-server messaging is attempted
 * but Redis is not available.
 */
class SimpleMessageBusWithLogging extends SimpleMessageBus {
    private static final Logger LOGGER = Logger.getLogger(SimpleMessageBusWithLogging.class.getName());
    
    public SimpleMessageBusWithLogging(String serverId) {
        super(serverId);
    }
    
    @Override
    public void broadcast(String type, Object payload) {
        LOGGER.warning("CROSS-SERVER MESSAGE ATTEMPTED: Broadcast of type '" + type + 
                      "' cannot be sent - Redis is not enabled. Enable Redis in database-config.yml for cross-server messaging.");
        super.broadcast(type, payload);
    }
    
    @Override
    public void send(String targetServerId, String type, Object payload) {
        LOGGER.warning("CROSS-SERVER MESSAGE ATTEMPTED: Message of type '" + type + 
                      "' to server '" + targetServerId + "' cannot be sent - Redis is not enabled. Enable Redis in database-config.yml for cross-server messaging.");
        super.send(targetServerId, type, payload);
    }
}