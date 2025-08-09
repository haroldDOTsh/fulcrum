package sh.harold.fulcrum.velocity.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

public class ConfigLoader {
    
    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(ConfigLoader.class);
    private final Path dataDirectory;
    private final ObjectMapper yamlMapper;
    private Map<String, Object> config;
    private ServerLifecycleConfig serverLifecycleConfig;
    private RedisConfig redisConfig;
    
    public ConfigLoader(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.config = new HashMap<>();
    }
    
    public void loadConfiguration() throws IOException {
        // Ensure data directory exists
        if (!Files.exists(dataDirectory)) {
            Files.createDirectories(dataDirectory);
        }
        
        Path configFile = dataDirectory.resolve("config.yml");
        
        // Copy default config if it doesn't exist
        if (!Files.exists(configFile)) {
            try (InputStream defaultConfig = getClass().getResourceAsStream("/config.yml")) {
                if (defaultConfig != null) {
                    Files.copy(defaultConfig, configFile, StandardCopyOption.REPLACE_EXISTING);
                    logger.info("Created default configuration file");
                }
            }
        }
        
        // Load configuration
        if (Files.exists(configFile)) {
            config = yamlMapper.readValue(configFile.toFile(), Map.class);
            logger.info("Loaded configuration from {}", configFile);
        } else {
            logger.warn("No configuration file found, using defaults");
            loadDefaults();
        }
        
        // Parse specific configs
        parseServerLifecycleConfig();
        parseRedisConfig();
    }
    
    private void loadDefaults() {
        config.put("server-lifecycle", Map.of(
            "enabled", true
        ));
        
        config.put("redis", Map.of(
            "enabled", false,
            "host", "localhost",
            "port", 6379,
            "password", "",
            "database", 0
        ));
        
        config.put("message-bus", Map.of(
            "enabled", true,
            "redis", Map.of(
                "host", "localhost",
                "port", 6379,
                "password", "",
                "database", 0
            ),
            "channel-prefix", "fulcrum"
        ));
    }
    
    @SuppressWarnings("unchecked")
    private void parseServerLifecycleConfig() {
        serverLifecycleConfig = new ServerLifecycleConfig();
        
        Map<String, Object> lifecycleSection = (Map<String, Object>) config.get("server-lifecycle");
        if (lifecycleSection != null) {
            Boolean enabled = (Boolean) lifecycleSection.get("enabled");
            if (enabled != null) {
                serverLifecycleConfig.setEnabled(enabled);
            }
        }
    }
    
    @SuppressWarnings("unchecked")
    private void parseRedisConfig() {
        redisConfig = new RedisConfig();
        
        Map<String, Object> redisSection = (Map<String, Object>) config.get("redis");
        if (redisSection != null) {
            Boolean enabled = (Boolean) redisSection.get("enabled");
            if (enabled != null) {
                redisConfig.setEnabled(enabled);
            }
            
            String host = (String) redisSection.get("host");
            if (host != null) {
                redisConfig.setHost(host);
            }
            
            Integer port = (Integer) redisSection.get("port");
            if (port != null) {
                redisConfig.setPort(port);
            }
            
            String password = (String) redisSection.get("password");
            if (password != null && !password.isEmpty()) {
                redisConfig.setPassword(password);
            }
            
            Integer database = (Integer) redisSection.get("database");
            if (database != null) {
                redisConfig.setDatabase(database);
            }
        }
    }
    
    public Map<String, Object> getConfig() {
        return config;
    }
    
    public ServerLifecycleConfig getServerLifecycleConfig() {
        if (serverLifecycleConfig == null) {
            serverLifecycleConfig = new ServerLifecycleConfig();
        }
        return serverLifecycleConfig;
    }
    
    public RedisConfig getRedisConfig() {
        if (redisConfig == null) {
            redisConfig = new RedisConfig();
        }
        return redisConfig;
    }
    
    @SuppressWarnings("unchecked")
    public <T> T get(String path, T defaultValue) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = config;
        
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (next instanceof Map) {
                current = (Map<String, Object>) next;
            } else {
                return defaultValue;
            }
        }
        
        Object value = current.get(parts[parts.length - 1]);
        return value != null ? (T) value : defaultValue;
    }
}