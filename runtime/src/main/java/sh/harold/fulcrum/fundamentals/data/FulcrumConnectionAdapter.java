package sh.harold.fulcrum.fundamentals.data;

import sh.harold.fulcrum.api.data.impl.json.JsonConnectionAdapter;
import sh.harold.fulcrum.api.data.impl.mongodb.MongoConnectionAdapter;
import sh.harold.fulcrum.api.data.storage.ConnectionAdapter;
import sh.harold.fulcrum.api.data.storage.StorageType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Map;
import java.util.logging.Logger;

public class FulcrumConnectionAdapter {
    private final JavaPlugin plugin;
    private final Logger logger;
    private ConnectionAdapter adapter;
    
    public FulcrumConnectionAdapter(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }
    
    public ConnectionAdapter createAdapter() {
        // Ensure plugin data folder exists
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
            logger.info("Created plugin data folder: " + plugin.getDataFolder().getAbsolutePath());
        }
        
        File configFile = new File(plugin.getDataFolder(), "database-config.yml");
        
        if (!configFile.exists()) {
            try {
                plugin.saveResource("database-config.yml", false);
                logger.info("Created default database-config.yml at: " + configFile.getAbsolutePath());
            } catch (Exception e) {
                logger.severe("Failed to save database-config.yml: " + e.getMessage());
                throw new RuntimeException("Could not create database configuration file", e);
            }
        } else {
            logger.info("Loading existing database-config.yml from: " + configFile.getAbsolutePath());
        }
        
        // Load and parse the configuration
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        
        // Validate that the config was loaded properly
        if (!configFile.exists()) {
            logger.severe("Database configuration file still does not exist after save attempt!");
            throw new RuntimeException("Database configuration file could not be created");
        }
        
        String storageTypeStr = config.getString("storage.type", "JSON");
        StorageType storageType;
        
        try {
            storageType = StorageType.valueOf(storageTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warning("Invalid storage type: " + storageTypeStr + ". Using JSON as default.");
            storageType = StorageType.JSON;
        }
        
        logger.info("Initializing data storage with backend: " + storageType);
        
        switch (storageType) {
            case JSON:
                String dataPath = config.getString("json.data-path", "data");
                File dataDir = new File(plugin.getDataFolder(), dataPath);
                if (!dataDir.exists()) {
                    dataDir.mkdirs();
                }
                
                boolean cacheEnabled = config.getBoolean("json.cache.enabled", true);
                int maxCacheSize = config.getInt("json.cache.max-size", 1000);
                
                Map<String, Object> jsonOptions = Map.of(
                    "path", dataDir.getAbsolutePath(),
                    "cacheEnabled", cacheEnabled,
                    "maxCacheSize", maxCacheSize
                );
                
                adapter = new JsonConnectionAdapter(dataDir.toPath());
                logger.info("Using JSON storage backend at: " + dataDir.getAbsolutePath());
                break;
                
            case MONGODB:
                String connectionString = config.getString("mongodb.connection-string", "mongodb://localhost:27017");
                String database = config.getString("mongodb.database", "fulcrum");
                String username = config.getString("mongodb.username", "");
                String password = config.getString("mongodb.password", "");
                
                Map<String, Object> mongoOptions = Map.of(
                    "connectionString", connectionString,
                    "database", database,
                    "username", username,
                    "password", password
                );
                
                adapter = new MongoConnectionAdapter(connectionString, database);
                logger.info("Using MongoDB storage backend: " + database);
                break;
                
            default:
                throw new IllegalStateException("Unsupported storage type: " + storageType);
        }
        
        return adapter;
    }
    
    public void shutdown() {
        if (adapter != null) {
            try {
                if (adapter instanceof MongoConnectionAdapter) {
                    ((MongoConnectionAdapter) adapter).close();
                }
                // JSON adapter doesn't need explicit disconnect
                logger.info("Data storage backend disconnected successfully");
            } catch (Exception e) {
                logger.warning("Error disconnecting data storage: " + e.getMessage());
            }
        }
    }
}