package sh.harold.fulcrum.fundamentals.messagebus;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.PlayerLocator;
import sh.harold.fulcrum.fundamentals.messagebus.commands.MessageDebugCommand;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
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
        
        // Check if development mode is enabled
        FileConfiguration config = plugin.getConfig();
        boolean devMode = config.getBoolean("development-mode", false);
        
        if (devMode) {
            // Development mode - always use SimpleMessageBus (no Redis)
            LOGGER.info("=== DEVELOPMENT MODE ENABLED ===");
            LOGGER.info("Using local-only message bus (SimpleMessageBus)");
            LOGGER.info("Redis is disabled in development mode");
            LOGGER.info("Perfect for single-server testing of minigames and features");
            
            this.messageBus = new SimpleMessageBus(tempNodeId);
            this.playerLocator = new SimplePlayerLocator(messageBus);
        } else {
            // Production mode - ALWAYS attempt to use Redis for cross-server features
            LOGGER.info("=== PRODUCTION MODE ===");
            LOGGER.info("Attempting to connect to Redis for cross-server features...");
            
            // Load database configuration for Redis settings
            File databaseConfigFile = new File(plugin.getDataFolder(), "database-config.yml");
            if (!databaseConfigFile.exists()) {
                plugin.saveResource("database-config.yml", false);
                databaseConfigFile = new File(plugin.getDataFolder(), "database-config.yml");
            }
            FileConfiguration databaseConfig = YamlConfiguration.loadConfiguration(databaseConfigFile);
            
            // Get Redis configuration (always present, no enabled flag needed)
            ConfigurationSection redisSection = databaseConfig.getConfigurationSection("redis");
            if (redisSection == null) {
                // In production mode, Redis configuration is REQUIRED - fail server startup
                LOGGER.severe("============================================");
                LOGGER.severe("CRITICAL: Redis configuration missing in database-config.yml!");
                LOGGER.severe("Server startup aborted - Redis is required for production servers");
                LOGGER.severe("To fix: Either add Redis configuration to database-config.yml OR enable development-mode in config.yml");
                LOGGER.severe("============================================");
                throw new RuntimeException("Redis configuration missing in production mode - server startup aborted");
            } else {
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
                    
                    // Try to initialize Redis message bus directly
                    try {
                        // Initialize Redis message bus - it uses Lettuce internally
                        LOGGER.info("Attempting to initialize Redis message bus...");
                        this.messageBus = new RedisMessageBus(tempNodeId, redisConfig);
                        
                        // Check if connected
                        if (messageBus instanceof RedisMessageBus && ((RedisMessageBus) messageBus).isConnected()) {
                            LOGGER.info("Redis connected successfully!");
                            LOGGER.info("Using Redis message bus for cross-server communication");
                            
                            // For now, use SimplePlayerLocator as RedisPlayerLocator expects different Redis client
                            // TODO: Implement proper Redis-based player locator with Lettuce
                            this.playerLocator = new SimplePlayerLocator(messageBus);
                        } else {
                            throw new RuntimeException("Redis connection check failed");
                        }
                    } catch (Exception redisInitError) {
                        // In production mode, Redis is REQUIRED
                        LOGGER.severe("============================================");
                        LOGGER.severe("CRITICAL: Redis connection failed in PRODUCTION MODE");
                        LOGGER.severe("Error: " + redisInitError.getMessage());
                        LOGGER.severe("Server will shut down in 2 seconds...");
                        LOGGER.severe("To fix: Either start Redis server OR enable development-mode in config.yml");
                        LOGGER.severe("Redis host: " + redisSection.getString("host", "localhost") + ":" + redisSection.getInt("port", 6379));
                        LOGGER.severe("============================================");
                        
                        // Still create a SimpleMessageBus to prevent dependency failures during shutdown
                        this.messageBus = new SimpleMessageBus(tempNodeId);
                        this.playerLocator = new SimplePlayerLocator(messageBus);
                        
                        // Register services to prevent cascading failures
                        container.register(MessageBus.class, messageBus);
                        container.register(PlayerLocator.class, playerLocator);
                        
                        // Schedule server shutdown
                        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                            plugin.getLogger().severe("Shutting down server due to Redis failure in production mode");
                            plugin.getServer().shutdown();
                        }, 40L); // 2 seconds
                        
                        throw new RuntimeException("Redis connection failed in production mode - server shutdown scheduled");
                    }
                } catch (RuntimeException re) {
                    // Re-throw runtime exceptions to stop server startup
                    throw re;
                } catch (Exception e) {
                    // In production mode, any Redis initialization error should fail server startup but still register basic services
                    LOGGER.severe("============================================");
                    LOGGER.severe("CRITICAL: Redis connection failed in PRODUCTION MODE");
                    LOGGER.severe("Server will shut down in 2 seconds...");
                    LOGGER.severe("Error: " + e.getMessage());
                    LOGGER.severe("To fix: Either start Redis server OR enable development-mode in config.yml");
                    LOGGER.severe("============================================");
                    
                    // Still create a SimpleMessageBus to prevent dependency failures during shutdown
                    this.messageBus = new SimpleMessageBus(tempNodeId);
                    this.playerLocator = new SimplePlayerLocator(messageBus);
                    
                    // Register services to prevent cascading failures
                    container.register(MessageBus.class, messageBus);
                    container.register(PlayerLocator.class, playerLocator);
                    
                    // Schedule server shutdown
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        plugin.getLogger().severe("Shutting down server due to Redis failure in production mode");
                        plugin.getServer().shutdown();
                    }, 40L); // 2 seconds
                    
                    throw new RuntimeException("Redis initialization failed in production mode: " + e.getMessage(), e);
                }
            }
        }
        
        // Register services
        container.register(MessageBus.class, messageBus);
        container.register(PlayerLocator.class, playerLocator);
        
        // Register debug command
        MessageDebugCommand debugCommand = new MessageDebugCommand(container);
        CommandRegistrar.register(debugCommand.build());
        LOGGER.info("Message debug command registered (/fulcrum messagedebug)");
        
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
        
        // RedisMessageBus handles its own connections and cleanup
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