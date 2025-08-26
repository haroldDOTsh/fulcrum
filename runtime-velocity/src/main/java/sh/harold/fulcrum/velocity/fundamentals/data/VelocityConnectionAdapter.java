package sh.harold.fulcrum.velocity.fundamentals.data;

import sh.harold.fulcrum.api.data.impl.json.JsonConnectionAdapter;
import sh.harold.fulcrum.api.data.impl.mongodb.MongoConnectionAdapter;
import sh.harold.fulcrum.api.data.storage.ConnectionAdapter;
import sh.harold.fulcrum.api.data.storage.StorageType;
import org.yaml.snakeyaml.Yaml;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class VelocityConnectionAdapter {
    private final Path dataDirectory;
    private final Logger logger;
    private ConnectionAdapter adapter;
    
    public VelocityConnectionAdapter(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
    }
    
    public ConnectionAdapter createAdapter() {
        Path configFile = dataDirectory.resolve("database-config.yml");
        
        // Copy default config if it doesn't exist
        if (!Files.exists(configFile)) {
            try {
                Files.createDirectories(dataDirectory);
                try (InputStream in = getClass().getResourceAsStream("/database-config.yml")) {
                    if (in != null) {
                        Files.copy(in, configFile);
                        logger.info("Created default database-config.yml");
                    }
                }
            } catch (IOException e) {
                logger.error("Failed to create database-config.yml", e);
            }
        }
        
        // Load configuration
        Map<String, Object> config = loadConfig(configFile);
        Map<String, Object> storage = (Map<String, Object>) config.getOrDefault("storage", Map.of());
        String storageTypeStr = (String) storage.getOrDefault("type", "JSON");
        
        StorageType storageType;
        try {
            storageType = StorageType.valueOf(storageTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid storage type: {}. Using JSON as default.", storageTypeStr);
            storageType = StorageType.JSON;
        }
        
        logger.info("Initializing data storage with backend: {}", storageType);
        
        switch (storageType) {
            case JSON:
                Map<String, Object> jsonConfig = (Map<String, Object>) config.getOrDefault("json", Map.of());
                String dataPath = (String) jsonConfig.getOrDefault("data-path", "data");
                Path dataDir = dataDirectory.resolve(dataPath);
                
                try {
                    Files.createDirectories(dataDir);
                } catch (IOException e) {
                    logger.error("Failed to create data directory", e);
                }
                
                Map<String, Object> cache = (Map<String, Object>) jsonConfig.getOrDefault("cache", Map.of());
                boolean cacheEnabled = (Boolean) cache.getOrDefault("enabled", true);
                int maxCacheSize = ((Number) cache.getOrDefault("max-size", 1000)).intValue();
                
                Map<String, Object> jsonOptions = Map.of(
                    "path", dataDir.toAbsolutePath().toString(),
                    "cacheEnabled", cacheEnabled,
                    "maxCacheSize", maxCacheSize
                );
                
                adapter = new JsonConnectionAdapter(jsonOptions);
                logger.info("Using JSON storage backend at: {}", dataDir.toAbsolutePath());
                break;
                
            case MONGODB:
                Map<String, Object> mongoConfig = (Map<String, Object>) config.getOrDefault("mongodb", Map.of());
                String connectionString = (String) mongoConfig.getOrDefault("connection-string", "mongodb://localhost:27017");
                String database = (String) mongoConfig.getOrDefault("database", "fulcrum_velocity");
                String username = (String) mongoConfig.getOrDefault("username", "");
                String password = (String) mongoConfig.getOrDefault("password", "");
                
                Map<String, Object> mongoOptions = Map.of(
                    "connectionString", connectionString,
                    "database", database,
                    "username", username,
                    "password", password
                );
                
                adapter = new MongoConnectionAdapter(mongoOptions);
                logger.info("Using MongoDB storage backend: {}", database);
                break;
                
            default:
                throw new IllegalStateException("Unsupported storage type: " + storageType);
        }
        
        return adapter;
    }
    
    private Map<String, Object> loadConfig(Path configFile) {
        try {
            Yaml yaml = new Yaml();
            return yaml.load(Files.newInputStream(configFile));
        } catch (Exception e) {
            logger.error("Failed to load database config, using defaults", e);
            return Map.of();
        }
    }
    
    public void shutdown() {
        if (adapter != null) {
            try {
                adapter.disconnect();
                logger.info("Data storage backend disconnected successfully");
            } catch (Exception e) {
                logger.warn("Error disconnecting data storage", e);
            }
        }
    }
}