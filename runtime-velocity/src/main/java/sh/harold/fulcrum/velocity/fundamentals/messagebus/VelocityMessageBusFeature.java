package sh.harold.fulcrum.velocity.fundamentals.messagebus;

import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusConnectionConfig;
import sh.harold.fulcrum.api.messagebus.impl.MessageBusFactory;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.config.ConfigLoader;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Message Bus Feature providing inter-server communication capabilities.
 * Uses clean adapter pattern with consolidated message-bus-api.
 */
public class VelocityMessageBusFeature implements VelocityFeature {
    
    private ProxyServer proxy;
    private FulcrumVelocityPlugin plugin;
    private ConfigLoader configLoader;
    private Logger logger;
    private MessageBus messageBus;
    private VelocityMessageBusAdapter adapter;
    private String proxyId;
    
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
        
        // Load configuration
        MessageBusConnectionConfig config = loadConfiguration();
        
        // Create Velocity-specific adapter
        adapter = new VelocityMessageBusAdapter(proxy, plugin, serviceLocator, config, logger);
        
        // Store proxy ID for later use
        proxyId = adapter.getServerId();
        logger.info("Using temporary proxy ID: {}", proxyId);
        
        // Create message bus using factory
        try {
            messageBus = MessageBusFactory.create(adapter);
            logger.info("MessageBus initialized with type: {}", config.getType());
            
        } catch (Exception e) {
            logger.error("Failed to create message bus: {}", e.getMessage());
            throw new RuntimeException("Failed to initialize message bus", e);
        }
        
        // Register services
        serviceLocator.register(MessageBus.class, messageBus);
        
        // Subscribe to basic messages
        setupMessageHandlers();
        
        logger.info("MessageBus feature initialized successfully");
        logger.info("Type: {}", config.getType());
        if (config.getType() == MessageBusConnectionConfig.MessageBusType.REDIS) {
            logger.info("Redis: {}:{}", config.getHost(), config.getPort());
        }
    }
    
    /**
     * Update the proxy ID when Registry Service assigns a permanent one
     */
    public void updateProxyId(String newProxyId) {
        if (adapter != null) {
            adapter.updateProxyId(newProxyId);
            this.proxyId = newProxyId;
            logger.info("Proxy ID updated to permanent ID: {}", newProxyId);
        }
    }
    
    /**
     * Get the current proxy ID (permanent if assigned, temporary otherwise)
     */
    public String getCurrentProxyId() {
        return proxyId;
    }
    
    @Override
    public void shutdown() {
        logger.info("Shutting down MessageBus feature");
        
        if (adapter != null) {
            adapter.shutdown();
        }
        
        if (messageBus != null) {
            try {
                // Message bus handles its own shutdown
                // The adapter will be notified via onMessageBusShutdown callback
            } catch (Exception e) {
                logger.warn("Error during message bus shutdown: {}", e.getMessage());
            }
        }
        
        messageBus = null;
        adapter = null;
        
        logger.info("MessageBus feature shutdown complete");
    }
    
    private MessageBusConnectionConfig loadConfiguration() {
        // Load Redis configuration from database-config.yml
        try {
            Path configPath = plugin.getDataDirectory().resolve("database-config.yml");
            
            // If config doesn't exist, copy default from resources
            if (!Files.exists(configPath)) {
                try (InputStream defaultConfig = getClass().getClassLoader()
                        .getResourceAsStream("database-config.yml")) {
                    if (defaultConfig != null) {
                        Files.createDirectories(plugin.getDataDirectory());
                        Files.copy(defaultConfig, configPath);
                        logger.info("Created default database-config.yml");
                    }
                }
            }
            
            // Load the configuration
            Yaml yaml = new Yaml();
            Map<String, Object> config;
            
            try (InputStream input = Files.newInputStream(configPath)) {
                config = yaml.load(input);
            }
            
            // Get Redis configuration section
            @SuppressWarnings("unchecked")
            Map<String, Object> redisSection = (Map<String, Object>) config.get("redis");
            
            if (redisSection == null) {
                logger.warn("No Redis configuration found in database-config.yml");
                logger.warn("Using in-memory message bus as fallback");
                return MessageBusConnectionConfig.inMemory();
            }
            
            // Check if Redis is enabled
            Boolean enabled = (Boolean) redisSection.get("enabled");
            if (enabled != null && !enabled) {
                logger.info("Redis is disabled in configuration");
                logger.info("Using in-memory message bus");
                return MessageBusConnectionConfig.inMemory();
            }
            
            // Build Redis configuration
            MessageBusConnectionConfig.Builder builder = MessageBusConnectionConfig.builder()
                .type(MessageBusConnectionConfig.MessageBusType.REDIS)
                .host((String) redisSection.getOrDefault("host", "localhost"))
                .port((Integer) redisSection.getOrDefault("port", 6379))
                .database((Integer) redisSection.getOrDefault("database", 0));
            
            // Set password if provided
            String password = (String) redisSection.get("password");
            if (password != null && !password.isEmpty()) {
                builder.password(password);
            }
            
            MessageBusConnectionConfig messageBusConfig = builder.build();
            logger.info("Redis configuration loaded successfully");
            return messageBusConfig;
            
        } catch (Exception e) {
            logger.error("Failed to load configuration: {}", e.getMessage());
            logger.warn("Falling back to in-memory message bus");
            return MessageBusConnectionConfig.inMemory();
        }
    }
    
    private void setupMessageHandlers() {
        // Subscribe to server lifecycle messages
        messageBus.subscribe("server.registration.response", envelope -> {
            logger.info("Received registration response");
        });
        
        // Note: Heartbeat handling is done in VelocityServerLifecycleFeature
        // to avoid duplicate processing
        
        logger.info("Message handlers registered");
    }
}