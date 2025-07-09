package sh.harold.fulcrum.runtime.config;

import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * YAML configuration loader for dirty cache configuration.
 * 
 * This class handles loading and parsing YAML configuration files
 * for the dirty data caching system.
 */
public class YamlConfigLoader {
    
    private static final Logger LOGGER = Logger.getLogger(YamlConfigLoader.class.getName());
    
    private final Yaml yaml;
    
    /**
     * Creates a new YAML configuration loader.
     */
    public YamlConfigLoader() {
        this.yaml = new Yaml();
    }
    
    /**
     * Loads dirty cache configuration from a YAML file.
     * 
     * @param configPath The path to the configuration file
     * @return The loaded configuration, or defaults if loading fails
     */
    public DirtyCacheConfig loadDirtyCacheConfig(String configPath) {
        try (InputStream inputStream = new FileInputStream(configPath)) {
            Map<String, Object> yamlData = yaml.load(inputStream);
            return parseDirtyCacheConfig(yamlData);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load cache configuration from: " + configPath, e);
            return DirtyCacheConfig.defaults();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to parse cache configuration", e);
            return DirtyCacheConfig.defaults();
        }
    }
    
    /**
     * Loads dirty cache configuration from a resource stream.
     * 
     * @param resourcePath The resource path
     * @return The loaded configuration, or defaults if loading fails
     */
    public DirtyCacheConfig loadDirtyCacheConfigFromResource(String resourcePath) {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                LOGGER.warning("Configuration resource not found: " + resourcePath);
                return DirtyCacheConfig.defaults();
            }
            
            Map<String, Object> yamlData = yaml.load(inputStream);
            return parseDirtyCacheConfig(yamlData);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load cache configuration from resource: " + resourcePath, e);
            return DirtyCacheConfig.defaults();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to parse cache configuration", e);
            return DirtyCacheConfig.defaults();
        }
    }
    
    /**
     * Parses dirty cache configuration from YAML data.
     * 
     * @param yamlData The YAML data map
     * @return The parsed configuration
     */
    private DirtyCacheConfig parseDirtyCacheConfig(Map<String, Object> yamlData) {
        if (yamlData == null) {
            return DirtyCacheConfig.defaults();
        }
        
        DirtyCacheConfig.Builder builder = DirtyCacheConfig.builder();
        
        // Parse cache section
        Map<String, Object> cacheSection = getSection(yamlData, "cache");
        if (cacheSection != null) {
            // Parse cache type
            String cacheType = getString(cacheSection, "type", "memory");
            try {
                builder.cacheType(DirtyCacheConfig.CacheType.fromConfigName(cacheType));
            } catch (IllegalArgumentException e) {
                LOGGER.warning("Invalid cache type: " + cacheType + ", using default");
                builder.cacheType(DirtyCacheConfig.CacheType.MEMORY);
            }
            
            // Parse fallback setting
            boolean fallbackToMemory = getBoolean(cacheSection, "fallback-to-memory", true);
            builder.fallbackToMemory(fallbackToMemory);
            
            // Parse entry TTL
            String entryTtlStr = getString(cacheSection, "entry-ttl", "1h");
            Duration entryTtl = parseDuration(entryTtlStr, Duration.ofHours(1));
            builder.entryTtl(entryTtl);
            
            // Parse health check interval
            String healthCheckStr = getString(cacheSection, "health-check-interval", "5m");
            Duration healthCheckInterval = parseDuration(healthCheckStr, Duration.ofMinutes(5));
            builder.healthCheckInterval(healthCheckInterval);
            
            // Parse Redis settings
            Map<String, Object> redisSection = getSection(cacheSection, "redis");
            if (redisSection != null) {
                DirtyCacheConfig.RedisSettings redisSettings = parseRedisSettings(redisSection);
                builder.redisSettings(redisSettings);
            }
        }
        
        return builder.build();
    }
    
    /**
     * Parses Redis settings from YAML data.
     * 
     * @param redisSection The Redis section from YAML
     * @return The parsed Redis settings
     */
    private DirtyCacheConfig.RedisSettings parseRedisSettings(Map<String, Object> redisSection) {
        DirtyCacheConfig.RedisSettingsBuilder builder = DirtyCacheConfig.RedisSettings.builder();
        
        // Connection settings
        String host = getString(redisSection, "host", "localhost");
        builder.host(host);
        
        int port = getInt(redisSection, "port", 6379);
        builder.port(port);
        
        int database = getInt(redisSection, "database", 0);
        builder.database(database);
        
        String password = getString(redisSection, "password", null);
        if (password != null && !password.isEmpty()) {
            builder.password(password);
        }
        
        // Timeout settings
        String connectionTimeoutStr = getString(redisSection, "connection-timeout", "5s");
        Duration connectionTimeout = parseDuration(connectionTimeoutStr, Duration.ofSeconds(5));
        builder.connectionTimeout(connectionTimeout);
        
        String retryDelayStr = getString(redisSection, "retry-delay", "500ms");
        Duration retryDelay = parseDuration(retryDelayStr, Duration.ofMillis(500));
        builder.retryDelay(retryDelay);
        
        int maxRetries = getInt(redisSection, "max-retries", 3);
        builder.maxRetries(maxRetries);
        
        // Pool settings
        Map<String, Object> poolSection = getSection(redisSection, "pool");
        if (poolSection != null) {
            int maxConnections = getInt(poolSection, "max-connections", 20);
            builder.maxConnections(maxConnections);
            
            int maxIdleConnections = getInt(poolSection, "max-idle-connections", 10);
            builder.maxIdleConnections(maxIdleConnections);
            
            int minIdleConnections = getInt(poolSection, "min-idle-connections", 5);
            builder.minIdleConnections(minIdleConnections);
        }
        
        return builder.build();
    }
    
    /**
     * Parses a duration string into a Duration object.
     * 
     * @param durationStr The duration string (e.g., "1h", "30m", "5s")
     * @param defaultValue The default value if parsing fails
     * @return The parsed duration
     */
    private Duration parseDuration(String durationStr, Duration defaultValue) {
        if (durationStr == null || durationStr.isEmpty()) {
            return defaultValue;
        }
        
        try {
            // Simple duration parsing - supports s, m, h suffixes
            durationStr = durationStr.trim().toLowerCase();
            
            if (durationStr.endsWith("ms")) {
                long millis = Long.parseLong(durationStr.substring(0, durationStr.length() - 2));
                return Duration.ofMillis(millis);
            } else if (durationStr.endsWith("s")) {
                long seconds = Long.parseLong(durationStr.substring(0, durationStr.length() - 1));
                return Duration.ofSeconds(seconds);
            } else if (durationStr.endsWith("m")) {
                long minutes = Long.parseLong(durationStr.substring(0, durationStr.length() - 1));
                return Duration.ofMinutes(minutes);
            } else if (durationStr.endsWith("h")) {
                long hours = Long.parseLong(durationStr.substring(0, durationStr.length() - 1));
                return Duration.ofHours(hours);
            } else {
                // Try to parse as seconds
                long seconds = Long.parseLong(durationStr);
                return Duration.ofSeconds(seconds);
            }
        } catch (NumberFormatException e) {
            LOGGER.warning("Invalid duration format: " + durationStr + ", using default: " + defaultValue);
            return defaultValue;
        }
    }
    
    /**
     * Gets a nested section from a YAML map.
     * 
     * @param map The parent map
     * @param key The section key
     * @return The nested section, or null if not found
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getSection(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return null;
    }
    
    /**
     * Gets a string value from a YAML map.
     * 
     * @param map The map
     * @param key The key
     * @param defaultValue The default value
     * @return The string value
     */
    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        if (value instanceof String) {
            return (String) value;
        }
        return defaultValue;
    }
    
    /**
     * Gets an integer value from a YAML map.
     * 
     * @param map The map
     * @param key The key
     * @param defaultValue The default value
     * @return The integer value
     */
    private int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }
    
    /**
     * Gets a boolean value from a YAML map.
     * 
     * @param map The map
     * @param key The key
     * @param defaultValue The default value
     * @return The boolean value
     */
    private boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return defaultValue;
    }
}