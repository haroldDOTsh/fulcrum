package sh.harold.fulcrum.velocity.fundamentals.messagebus;

import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.config.ConfigLoader;
import sh.harold.fulcrum.velocity.config.RedisConfig;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Feature that provides message bus functionality for the Velocity proxy.
 * Supports both simple (local) and Redis-based distributed messaging.
 * Implements Redis-based proxy slot allocation for unique proxy identification.
 */
public class VelocityMessageBusFeature implements VelocityFeature {
    
    private ProxyServer proxy;
    private FulcrumVelocityPlugin plugin;
    private ConfigLoader configLoader;
    private Logger logger;
    private MessageBus messageBus;
    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> redisConnection;
    private String proxyId;
    private int proxyIndex;
    
    @Override
    public String getName() {
        return "MessageBus";
    }
    
    @Override
    public int getPriority() {
        // Infrastructure layer - loads first
        return 10;
    }
    
    @Override
    public boolean isEnabled() {
        return true; // Always enabled
    }
    
    @Override
    public boolean isFundamental() {
        return true; // Core infrastructure feature
    }
    
    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) {
        this.logger = logger;
        
        // Get dependencies from service locator
        this.proxy = serviceLocator.getRequiredService(ProxyServer.class);
        this.plugin = serviceLocator.getRequiredService(FulcrumVelocityPlugin.class);
        this.configLoader = serviceLocator.getRequiredService(ConfigLoader.class);
        
        if (proxy == null || plugin == null || configLoader == null) {
            throw new IllegalStateException("Required dependencies not available");
        }
        
        logger.info("Initializing MessageBus feature");
        
        // Load message bus configuration
        MessageBusConfig config = configLoader.getConfig(MessageBusConfig.class);
        if (config == null) {
            config = new MessageBusConfig();
            config.setMode("redis"); // Default to Redis for production
        }
        
        // Create appropriate message bus based on configuration
        if (config.isRedisMode()) {
            RedisConfig redisConfig = configLoader.getConfig(RedisConfig.class);
            if (redisConfig == null) {
                logger.warn("Redis config not found, falling back to simple message bus");
                // Use simple proxy ID for non-Redis mode
                proxyId = "fulcrum-proxy-1";
                proxyIndex = 1;
                messageBus = new VelocitySimpleMessageBus(proxyId, proxy);
            } else {
                logger.info("Initializing Redis-based message bus");
                initializeRedisConnection(redisConfig);
                allocateProxySlot();
                messageBus = new VelocityRedisMessageBus(proxyId, proxy, redisConfig);
            }
        } else {
            logger.info("Using simple message bus");
            // Use simple proxy ID for non-Redis mode
            proxyId = "fulcrum-proxy-1";
            proxyIndex = 1;
            messageBus = new VelocitySimpleMessageBus(proxyId, proxy);
        }
        
        // Register message bus service BEFORE other features try to use it
        serviceLocator.register(MessageBus.class, messageBus);
        
        // Subscribe to server lifecycle messages
        messageBus.subscribe("server.registration.response", envelope -> {
            logger.info("Received registration response");
        });
        
        messageBus.subscribe("server.heartbeat", envelope -> {
            logger.debug("Received heartbeat message");
        });
        
        logger.info("MessageBus feature initialized with proxy ID: {} (index: {})", proxyId, proxyIndex);
    }
    
    private void initializeRedisConnection(RedisConfig config) {
        try {
            // Build Redis URI for Lettuce
            RedisURI.Builder uriBuilder = RedisURI.builder()
                .withHost(config.getHost())
                .withPort(config.getPort())
                .withDatabase(config.getDatabase())
                .withTimeout(java.time.Duration.ofMillis(2000));
            
            if (config.getPassword() != null && !config.getPassword().isEmpty()) {
                uriBuilder.withPassword(config.getPassword().toCharArray());
            }
            
            RedisURI redisURI = uriBuilder.build();
            
            // Create Redis client and connection
            redisClient = RedisClient.create(redisURI);
            redisConnection = redisClient.connect();
            
            // Test connection
            RedisCommands<String, String> commands = redisConnection.sync();
            String pong = commands.ping();
            logger.info("Connected to Redis at {}:{} - Response: {}", config.getHost(), config.getPort(), pong);
            
        } catch (Exception e) {
            logger.error("Failed to initialize Redis connection: {}", e.getMessage());
            if (redisConnection != null && redisConnection.isOpen()) {
                redisConnection.close();
            }
            if (redisClient != null) {
                redisClient.shutdown();
            }
            throw new RuntimeException("Redis connection failed", e);
        }
    }
    
    private void allocateProxySlot() {
        // Allocate a proxy slot using Redis SETNX for atomic operations
        try {
            RedisCommands<String, String> commands = redisConnection.sync();
            
            // Find the first available slot from 1-100
            for (int i = 1; i <= 100; i++) {
                String slotKey = "fulcrum:proxy:slot:" + i;
                
                // Try to claim the slot with TTL of 60 seconds using SetArgs for NX and EX
                SetArgs setArgs = SetArgs.Builder.nx().ex(60);
                String result = commands.set(slotKey, String.valueOf(System.currentTimeMillis()), setArgs);
                
                if ("OK".equals(result)) {
                    // Successfully claimed the slot
                    proxyIndex = i;
                    proxyId = "fulcrum-proxy-" + i;
                    
                    // Start heartbeat to maintain the slot
                    startSlotHeartbeat(slotKey);
                    
                    logger.info("Allocated proxy slot: {}", i);
                    return;
                }
            }
            
            // No slots available, use overflow slot with timestamp
            logger.warn("All proxy slots full, using overflow slot");
            proxyIndex = 999;
            proxyId = "fulcrum-proxy-overflow-" + System.currentTimeMillis();
            
        } catch (Exception e) {
            logger.error("Failed to allocate proxy slot, using fallback: {}", e.getMessage());
            proxyId = "fulcrum-proxy-fallback";
            proxyIndex = 0;
        }
    }
    
    private void startSlotHeartbeat(String slotKey) {
        // Start a scheduled task to refresh the TTL every 30 seconds
        proxy.getScheduler()
            .buildTask(plugin, () -> {
                try {
                    RedisCommands<String, String> commands = redisConnection.sync();
                    Boolean result = commands.expire(slotKey, 60); // Refresh TTL to 60 seconds
                    if (Boolean.TRUE.equals(result)) {
                        logger.debug("Refreshed TTL for proxy slot {}", proxyIndex);
                    } else {
                        logger.warn("Failed to refresh TTL for proxy slot {} - key may not exist", proxyIndex);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to refresh proxy slot TTL: {}", e.getMessage());
                }
            })
            .repeat(Duration.ofSeconds(30))
            .schedule();
    }
    
    @Override
    public void shutdown() {
        // Release the proxy slot if using Redis
        if (redisConnection != null && redisConnection.isOpen() && proxyIndex > 0 && proxyIndex <= 100) {
            try {
                RedisCommands<String, String> commands = redisConnection.sync();
                String slotKey = "fulcrum:proxy:slot:" + proxyIndex;
                Long deleted = commands.del(slotKey);
                if (deleted > 0) {
                    logger.info("Released proxy slot: {}", proxyIndex);
                }
            } catch (Exception e) {
                logger.warn("Failed to release proxy slot: {}", e.getMessage());
            }
        }
        
        if (messageBus != null) {
            logger.info("Shutting down message bus");
            
            if (messageBus instanceof VelocitySimpleMessageBus) {
                ((VelocitySimpleMessageBus) messageBus).shutdown();
            } else if (messageBus instanceof VelocityRedisMessageBus) {
                ((VelocityRedisMessageBus) messageBus).shutdown();
            }
        }
        
        // Close Redis connection and client
        if (redisConnection != null && redisConnection.isOpen()) {
            redisConnection.close();
        }
        
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }
    
    /**
     * Get the allocated proxy ID
     */
    public String getProxyId() {
        return proxyId;
    }
    
    /**
     * Get the allocated proxy index
     */
    public int getProxyIndex() {
        return proxyIndex;
    }
}