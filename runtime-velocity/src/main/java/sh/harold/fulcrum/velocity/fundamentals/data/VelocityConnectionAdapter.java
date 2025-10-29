package sh.harold.fulcrum.velocity.fundamentals.data;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;
import sh.harold.fulcrum.api.data.impl.json.JsonConnectionAdapter;
import sh.harold.fulcrum.api.data.impl.mongodb.MongoConnectionAdapter;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.api.data.storage.ConnectionAdapter;
import sh.harold.fulcrum.api.data.storage.StorageType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

public class VelocityConnectionAdapter {
    private final Path dataDirectory;
    private final Logger logger;
    private ConnectionAdapter adapter;
    private PostgresConnectionAdapter postgresAdapter;
    private MongoConnectionAdapter mongoAdapter;

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
        postgresAdapter = null;
        mongoAdapter = null;

        Map<String, Object> config = loadConfig(configFile);
        Map<String, Object> storage = castMap(config.getOrDefault("storage", Map.of()));
        if (storage == null) {
            storage = Map.of();
        }
        String storageTypeStr = String.valueOf(storage.getOrDefault("type", "MONGODB"));

        StorageType storageType;
        try {
            storageType = StorageType.valueOf(storageTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid storage type: {}. Using JSON as default.", storageTypeStr);
            storageType = StorageType.MONGODB;
        }

        logger.info("Initializing data storage with backend: {}", storageType);

        switch (storageType) {
            case JSON:
                Map<String, Object> jsonConfig = castMap(config.getOrDefault("json", Map.of()));
                if (jsonConfig == null) {
                    jsonConfig = Map.of();
                }
                String dataPath = String.valueOf(jsonConfig.getOrDefault("data-path", "data"));
                Path dataDir = dataDirectory.resolve(dataPath);

                try {
                    Files.createDirectories(dataDir);
                } catch (IOException e) {
                    logger.error("Failed to create data directory", e);
                }

                Map<String, Object> cache = castMap(jsonConfig.getOrDefault("cache", Map.of()));
                if (cache == null) {
                    cache = Map.of();
                }
                boolean cacheEnabled = Boolean.parseBoolean(String.valueOf(cache.getOrDefault("enabled", true)));
                int maxCacheSize = ((Number) cache.getOrDefault("max-size", 1000)).intValue();

                // JsonConnectionAdapter expects a Path directly
                adapter = new JsonConnectionAdapter(dataDir);
                logger.info("Using JSON storage backend at: {}", dataDir.toAbsolutePath());
                if (cacheEnabled) {
                    logger.info("Cache enabled with max size: {}", maxCacheSize);
                }
                break;

            case MONGODB:
                Map<String, Object> mongoConfig = castMap(config.getOrDefault("mongodb", Map.of()));
                if (mongoConfig == null) {
                    mongoConfig = Map.of();
                }
                String connectionString = String.valueOf(mongoConfig.getOrDefault("connection-string", "mongodb://localhost:27017"));
                String database = String.valueOf(mongoConfig.getOrDefault("database", "fulcrum_velocity"));
                String username = String.valueOf(mongoConfig.getOrDefault("username", ""));
                String password = String.valueOf(mongoConfig.getOrDefault("password", ""));

                // Build connection string with credentials if provided
                if (!username.isEmpty() && !password.isEmpty()) {
                    String[] parts = connectionString.split("://");
                    if (parts.length == 2) {
                        connectionString = parts[0] + "://" + username + ":" + password + "@" + parts[1];
                    }
                }

                // MongoConnectionAdapter expects connectionString and database name
                mongoAdapter = new MongoConnectionAdapter(connectionString, database);
                adapter = mongoAdapter;
                logger.info("Using MongoDB storage backend: {}", database);
                break;

            default:
                throw new IllegalStateException("Unsupported storage type: " + storageType);
        }

        initializePostgresAdapter(config);
        return adapter;
    }

    private Map<String, Object> loadConfig(Path configFile) {
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> map = yaml.load(Files.newInputStream(configFile));
            return map != null ? map : Map.of();
        } catch (Exception e) {
            logger.error("Failed to load database config, using defaults", e);
            return Map.of();
        }
    }

    private void initializePostgresAdapter(Map<String, Object> config) {
        Map<String, Object> postgresConfig = castMap(config.get("postgres"));
        if (postgresConfig == null) {
            logger.warn("database-config.yml missing 'postgres' section; relational features will be unavailable.");
            postgresAdapter = null;
            return;
        }

        boolean enabled = Boolean.parseBoolean(String.valueOf(postgresConfig.getOrDefault("enabled", true)));
        if (!enabled) {
            logger.warn("PostgreSQL configuration disabled; relational features will be unavailable.");
            postgresAdapter = null;
            return;
        }

        String jdbcUrl = String.valueOf(postgresConfig.getOrDefault("jdbc-url", "jdbc:postgresql://localhost:5432/fulcrum"));
        String database = String.valueOf(postgresConfig.getOrDefault("database", "fulcrum"));
        String username = String.valueOf(postgresConfig.getOrDefault("username", "fulcrum"));
        String password = String.valueOf(postgresConfig.getOrDefault("password", ""));

        try {
            PostgresConnectionAdapter adapter = new PostgresConnectionAdapter(jdbcUrl, username, password, database);
            try (Connection connection = adapter.getConnection()) {
                if (!connection.isValid(5)) {
                    throw new SQLException("PostgreSQL connection validation failed for database '" + database + "'");
                }
            }
            postgresAdapter = adapter;
            logger.info("PostgreSQL adapter initialized for database '{}'.", database);
        } catch (Exception ex) {
            logger.warn("Failed to initialize PostgreSQL adapter", ex);
            if (postgresAdapter != null) {
                try {
                    postgresAdapter.close();
                } catch (Exception ignored) {
                }
            }
            postgresAdapter = null;
        }
    }

    public void shutdown() {
        if (adapter != null) {
            try {
                // ConnectionAdapter doesn't have disconnect() method
                // Check if it's a MongoConnectionAdapter which has close()
                if (adapter instanceof MongoConnectionAdapter mongo) {
                    mongo.close();
                    logger.info("MongoDB storage backend disconnected successfully");
                }
                // JsonConnectionAdapter doesn't need explicit cleanup
            } catch (Exception e) {
                logger.warn("Error disconnecting data storage", e);
            }
        }
        if (postgresAdapter != null) {
            try {
                postgresAdapter.close();
                logger.info("PostgreSQL adapter disconnected successfully");
            } catch (Exception e) {
                logger.warn("Error disconnecting PostgreSQL adapter", e);
            } finally {
                postgresAdapter = null;
            }
        }
    }

    public PostgresConnectionAdapter getPostgresAdapter() {
        return postgresAdapter;
    }

    public MongoConnectionAdapter getMongoAdapter() {
        return mongoAdapter;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }
}
