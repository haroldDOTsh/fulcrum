package sh.harold.fulcrum.velocity.fundamentals.data;

import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import org.yaml.snakeyaml.Yaml;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

public class VelocityConnectionAdapter {
    private final Path dataDirectory;
    private final Logger logger;
    private PostgresConnectionAdapter adapter;
    
    public VelocityConnectionAdapter(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
    }
    
    public PostgresConnectionAdapter createAdapter() {
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
        Map<String, Object> postgresConfig = (Map<String, Object>) config.get("postgres");
        if (postgresConfig == null) {
            throw new IllegalStateException("database-config.yml must define a postgres section for Data Authority");
        }

        String jdbcUrl = (String) postgresConfig.getOrDefault("jdbc-url", "jdbc:postgresql://localhost:5432/fulcrum");
        String postgresDatabase = (String) postgresConfig.getOrDefault("database", "fulcrum_velocity");
        String postgresUsername = (String) postgresConfig.getOrDefault("username", "fulcrum_user");
        String postgresPassword = (String) postgresConfig.getOrDefault("password", "");

        adapter = new PostgresConnectionAdapter(
            jdbcUrl,
            postgresUsername,
            postgresPassword,
            postgresDatabase,
            postgresPoolProperties(postgresConfig)
        );
        logger.info("Using PostgreSQL authority backend: {}", postgresDatabase);
        
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

    private Properties postgresPoolProperties(Map<String, Object> postgresConfig) {
        Properties properties = new Properties();
        Map<String, Object> pool = (Map<String, Object>) postgresConfig.getOrDefault("connection-pool", Map.of());
        pool.forEach((key, value) -> properties.setProperty(key, String.valueOf(value)));
        return properties;
    }
    
    public void shutdown() {
        if (adapter != null) {
            try {
                adapter.close();
                logger.info("PostgreSQL authority backend disconnected successfully");
            } catch (Exception e) {
                logger.warn("Error disconnecting data storage", e);
            }
        }
    }
}
