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
    private String tempProxyId;  // Temporary ID until Registry assigns permanent one
    private String permanentProxyId;  // Permanent ID from Registry
    private int proxyIndex = -1;  // -1 indicates no permanent assignment yet
    
    @Override
    public String getName() {
        return "MessageBus";
    }
    
    @Override
    public int getPriority() {
        // Infrastructure layer - loads first (lower number = higher priority)
        return 10;
    }
    
    @Override
    public boolean isEnabled() {
        return true; // Always enabled
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
        
        // Generate temporary ID for initial use
        tempProxyId = "temp-proxy-" + java.util.UUID.randomUUID().toString();
        logger.info("Generated temporary proxy ID: {}", tempProxyId);
        
        // Create appropriate message bus based on configuration
        if (config.isRedisMode()) {
            RedisConfig redisConfig = configLoader.getConfig(RedisConfig.class);
            if (redisConfig == null) {
                logger.warn("Redis config not found, falling back to simple message bus");
                messageBus = new VelocitySimpleMessageBus(tempProxyId, proxy);
            } else {
                logger.info("Initializing Redis-based message bus with temporary ID");
                initializeRedisConnection(redisConfig);
                // Do NOT allocate proxy slot - Registry will assign the ID
                messageBus = new VelocityRedisMessageBus(tempProxyId, proxy, redisConfig);
            }
        } else {
            logger.info("Using simple message bus with temporary ID");
            messageBus = new VelocitySimpleMessageBus(tempProxyId, proxy);
        }
        
        // Register message bus service BEFORE other features try to use it
        serviceLocator.register(MessageBus.class, messageBus);
        
        // Subscribe to server lifecycle messages
        messageBus.subscribe("server.registration.response", envelope -> {
            logger.info("Received registration response");
        });
        
        messageBus.subscribe("server:heartbeat", envelope -> {
            logger.debug("Received heartbeat message");
        });
        
        logger.info("MessageBus feature initialized with temporary ID: {}", tempProxyId);
        logger.info("Waiting for Registry Service to assign permanent proxy ID...");
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
    
    /**
     * Update the proxy ID when Registry Service assigns a permanent one
     */
    public void updateProxyId(String newProxyId) {
        String oldId = getCurrentProxyId();
        this.permanentProxyId = newProxyId;
        
        // Extract index from permanent ID if it follows the pattern
        if (newProxyId != null && newProxyId.startsWith("fulcrum-proxy-")) {
            try {
                String indexStr = newProxyId.substring("fulcrum-proxy-".length());
                this.proxyIndex = Integer.parseInt(indexStr);
            } catch (NumberFormatException e) {
                this.proxyIndex = 0;
            }
        }
        
        logger.info("Updated proxy ID from {} to {} (index: {})", oldId, newProxyId, proxyIndex);
        
        // Note: The message bus instances will continue using the old ID internally,
        // but VelocityServerLifecycleFeature will use the updated ID for all external communications
    }
    
    /**
     * Get the current proxy ID (permanent if assigned, temporary otherwise)
     */
    public String getCurrentProxyId() {
        return permanentProxyId != null ? permanentProxyId : tempProxyId;
    }
    
    @Override
    public void shutdown() {
        // No slot to release since Registry Service manages IDs
        
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
     * Get the proxy ID (for backward compatibility)
     * @deprecated Use getCurrentProxyId() instead
     */
    @Deprecated
    public String getProxyId() {
        return getCurrentProxyId();
    }
    
    /**
     * Get the proxy index (-1 if no permanent ID assigned yet)
     */
    public int getProxyIndex() {
        return proxyIndex;
    }
    
    /**
     * Check if a permanent proxy ID has been assigned
     */
    public boolean hasPermanentId() {
        return permanentProxyId != null;
    }
}