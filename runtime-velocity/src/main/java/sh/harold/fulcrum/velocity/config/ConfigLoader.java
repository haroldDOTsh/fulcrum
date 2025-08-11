package sh.harold.fulcrum.velocity.config;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;
import sh.harold.fulcrum.velocity.fundamentals.messagebus.MessageBusConfig;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class ConfigLoader {
    private final Path dataDirectory;
    private final Logger logger;
    private final Map<String, Object> configuration;
    private final Map<Class<?>, Object> configCache;
    
    public ConfigLoader(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
        this.configuration = new HashMap<>();
        this.configCache = new HashMap<>();
        loadConfiguration();
    }
    
    private void loadConfiguration() {
        Path configFile = dataDirectory.resolve("config.yml");
        
        // Create default config if it doesn't exist
        if (!Files.exists(configFile)) {
            createDefaultConfig(configFile);
        }
        
        // Load configuration
        try (InputStream input = Files.newInputStream(configFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> loaded = yaml.load(input);
            if (loaded != null) {
                configuration.putAll(loaded);
            }
            logger.info("Configuration loaded from {}", configFile);
        } catch (IOException e) {
            logger.error("Failed to load configuration", e);
        }
        
        // Parse specific configurations
        parseConfigurations();
    }
    
    private void createDefaultConfig(Path configFile) {
        try {
            Files.createDirectories(configFile.getParent());
            try (InputStream defaultConfig = getClass().getResourceAsStream("/config.yml")) {
                if (defaultConfig != null) {
                    Files.copy(defaultConfig, configFile);
                    logger.info("Created default configuration at {}", configFile);
                }
            }
        } catch (IOException e) {
            logger.error("Failed to create default configuration", e);
        }
    }
    
    @SuppressWarnings("unchecked")
    private void parseConfigurations() {
        // Parse Message Bus configuration
        Map<String, Object> messageBusSection = (Map<String, Object>) configuration.get("message-bus");
        if (messageBusSection != null) {
            MessageBusConfig messageBusConfig = new MessageBusConfig();
            messageBusConfig.setMode((String) messageBusSection.getOrDefault("mode", "redis"));
            configCache.put(MessageBusConfig.class, messageBusConfig);
        } else {
            // Default to Redis for proxies if not specified
            MessageBusConfig messageBusConfig = new MessageBusConfig();
            messageBusConfig.setMode("redis");
            configCache.put(MessageBusConfig.class, messageBusConfig);
        }
        
        // Parse Redis configuration
        Map<String, Object> redisSection = (Map<String, Object>) configuration.get("redis");
        if (redisSection != null) {
            RedisConfig redisConfig = new RedisConfig();
            redisConfig.setEnabled((Boolean) redisSection.getOrDefault("enabled", false));
            redisConfig.setHost((String) redisSection.getOrDefault("host", "localhost"));
            redisConfig.setPort((Integer) redisSection.getOrDefault("port", 6379));
            redisConfig.setPassword((String) redisSection.getOrDefault("password", ""));
            redisConfig.setDatabase((Integer) redisSection.getOrDefault("database", 0));
            redisConfig.setTimeout((Integer) redisSection.getOrDefault("timeout", 2000));
            
            Map<String, Object> poolSection = (Map<String, Object>) redisSection.get("pool");
            if (poolSection != null) {
                redisConfig.setMaxTotal((Integer) poolSection.getOrDefault("maxTotal", 8));
                redisConfig.setMaxIdle((Integer) poolSection.getOrDefault("maxIdle", 8));
                redisConfig.setMinIdle((Integer) poolSection.getOrDefault("minIdle", 0));
            }
            
            configCache.put(RedisConfig.class, redisConfig);
        }
        
        // Parse Server Lifecycle configuration
        Map<String, Object> lifecycleSection = (Map<String, Object>) configuration.get("server-lifecycle");
        if (lifecycleSection != null) {
            ServerLifecycleConfig lifecycleConfig = new ServerLifecycleConfig();
            
            Map<String, Object> registrationSection = (Map<String, Object>) lifecycleSection.get("registration");
            if (registrationSection != null) {
                lifecycleConfig.setRegistrationEnabled((Boolean) registrationSection.getOrDefault("enabled", true));
                lifecycleConfig.setHeartbeatInterval((Integer) registrationSection.getOrDefault("interval", 30));
                lifecycleConfig.setTimeoutSeconds((Integer) registrationSection.getOrDefault("timeout", 90));
            }
            
            Map<String, Object> capacitySection = (Map<String, Object>) lifecycleSection.get("capacity");
            if (capacitySection != null) {
                lifecycleConfig.setCapacityMode((String) capacitySection.getOrDefault("mode", "dynamic"));
                lifecycleConfig.setStaticCapacity((Integer) capacitySection.getOrDefault("static-value", 100));
            }
            
            Map<String, Object> typeSection = (Map<String, Object>) lifecycleSection.get("type-detection");
            if (typeSection != null) {
                lifecycleConfig.setTypeDetectionMode((String) typeSection.getOrDefault("mode", "auto"));
                lifecycleConfig.setManualType((String) typeSection.getOrDefault("manual-type", "proxy"));
            }
            
            configCache.put(ServerLifecycleConfig.class, lifecycleConfig);
        }
    }
    
    @SuppressWarnings("unchecked")
    public <T> T getConfig(Class<T> configClass) {
        return (T) configCache.get(configClass);
    }
    
    public Map<String, Object> getConfiguration() {
        return new HashMap<>(configuration);
    }
    
    public Object get(String key) {
        return configuration.get(key);
    }
    
    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defaultValue) {
        Object value = configuration.get(key);
        return value != null ? (T) value : defaultValue;
    }
}