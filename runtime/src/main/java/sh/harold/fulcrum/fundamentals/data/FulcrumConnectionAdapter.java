package sh.harold.fulcrum.fundamentals.data;

import sh.harold.fulcrum.api.data.impl.json.JsonConnectionAdapter;
import sh.harold.fulcrum.api.data.impl.mongodb.MongoConnectionAdapter;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.api.data.storage.ConnectionAdapter;
import sh.harold.fulcrum.api.data.storage.StorageType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FulcrumConnectionAdapter {
    private final JavaPlugin plugin;
    private final Logger logger;
    private ConnectionAdapter adapter;
    private PostgresConnectionAdapter postgresAdapter;
    
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
        
        String storageTypeStr = config.getString("storage.type", "MONGODB");
        StorageType storageType;

        try {
            storageType = StorageType.valueOf(storageTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warning("Invalid storage type: " + storageTypeStr + ". Using MONGODB as default.");
            storageType = StorageType.MONGODB;
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
                
                adapter = new JsonConnectionAdapter(dataDir.toPath());
                logger.info("Using JSON storage backend at: " + dataDir.getAbsolutePath());
                logger.info("JSON cache configuration: enabled=" + cacheEnabled + ", max-size=" + maxCacheSize);
                break;
                
            case MONGODB:
                String connectionString = config.getString("mongodb.connection-string", "mongodb://localhost:27017");
                String database = config.getString("mongodb.database", "fulcrum");
                String username = config.getString("mongodb.username", "");
                String password = config.getString("mongodb.password", "");
                
                adapter = new MongoConnectionAdapter(connectionString, database);
                logger.info("Using MongoDB storage backend: " + database);
                break;
                
            default:
                throw new IllegalStateException("Unsupported storage type: " + storageType);
        }
        
        initializePostgresAdapter(config);
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

        if (postgresAdapter != null) {
            try {
                postgresAdapter.close();
                logger.info("PostgreSQL adapter disconnected successfully");
            } catch (Exception e) {
                logger.warning("Error disconnecting PostgreSQL adapter: " + e.getMessage());
            } finally {
                postgresAdapter = null;
            }
        }
    }

    public PostgresConnectionAdapter getPostgresAdapter() {
        return postgresAdapter;
    }

    private void initializePostgresAdapter(YamlConfiguration config) {
        if (!config.isConfigurationSection("postgres")) {
            logger.warning("database-config.yml missing 'postgres' section; relational features will be unavailable.");
            postgresAdapter = null;
            return;
        }

        boolean enabled = config.getBoolean("postgres.enabled", true);
        if (!enabled) {
            logger.warning("PostgreSQL configuration disabled; relational features will be unavailable.");
            postgresAdapter = null;
            return;
        }

        String jdbcUrl = config.getString("postgres.jdbc-url", "jdbc:postgresql://localhost:5432/fulcrum");
        String database = config.getString("postgres.database", "fulcrum");
        String username = config.getString("postgres.username", "fulcrum_user");
        String password = config.getString("postgres.password", "");

        try {
            PostgresConnectionAdapter adapter = new PostgresConnectionAdapter(jdbcUrl, username, password, database);
            try (Connection connection = adapter.getConnection()) {
                if (!connection.isValid(5)) {
                    throw new SQLException("PostgreSQL connection validation failed for database '" + database + "'");
                }
            }

            postgresAdapter = adapter;
            logger.info("PostgreSQL adapter initialized for database '" + database + "'.");
        } catch (Exception ex) {
            logger.log(Level.SEVERE, "Failed to initialize PostgreSQL adapter", ex);
            if (postgresAdapter != null) {
                try {
                    postgresAdapter.close();
                } catch (Exception ignored) {
                }
            }
            postgresAdapter = null;
        }
    }
}
