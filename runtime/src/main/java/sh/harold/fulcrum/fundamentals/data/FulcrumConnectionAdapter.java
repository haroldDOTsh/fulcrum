package sh.harold.fulcrum.fundamentals.data;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresMigrationRunner;

import java.io.File;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

public class FulcrumConnectionAdapter {
    private final JavaPlugin plugin;
    private final Logger logger;
    private PostgresConnectionAdapter adapter;
    
    public FulcrumConnectionAdapter(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }
    
    public PostgresConnectionAdapter createAdapter() {
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
        
        if (!config.contains("postgres")) {
            throw new RuntimeException("database-config.yml must define a postgres section for Data Authority");
        }

        String jdbcUrl = config.getString("postgres.jdbc-url", "jdbc:postgresql://localhost:5432/fulcrum");
        String postgresDatabase = config.getString("postgres.database", "fulcrum");
        String postgresUsername = config.getString("postgres.username", "fulcrum_user");
        String postgresPassword = config.getString("postgres.password", "");

        adapter = new PostgresConnectionAdapter(
            jdbcUrl,
            postgresUsername,
            postgresPassword,
            postgresDatabase,
            postgresPoolProperties(config)
        );
        try (var connection = adapter.getConnection()) {
            if (connection.isValid(5)) {
                logger.info("Connected to PostgreSQL database '" + postgresDatabase + "'");
                runPostgresMigrations(config, adapter);
            } else {
                logger.severe("PostgreSQL connection reported invalid for database '" + postgresDatabase + "'");
                throw new RuntimeException("PostgreSQL connection validation failed");
            }
        } catch (SQLException ex) {
            logger.severe("Failed to connect to PostgreSQL (" + postgresDatabase + "): " + ex.getMessage());
            adapter.close();
            throw new RuntimeException("Unable to establish PostgreSQL connection", ex);
        }
        
        return adapter;
    }

    private Properties postgresPoolProperties(YamlConfiguration config) {
        Properties properties = new Properties();
        ConfigurationSection poolSection = config.getConfigurationSection("postgres.connection-pool");
        if (poolSection == null) {
            return properties;
        }

        for (String key : poolSection.getKeys(false)) {
            properties.setProperty(key, String.valueOf(poolSection.get(key)));
        }
        return properties;
    }

    private void runPostgresMigrations(YamlConfiguration config, PostgresConnectionAdapter adapter) {
        boolean migrationsEnabled = config.getBoolean("postgres.migrations.enabled", true);
        boolean autoMigrate = config.getBoolean("postgres.migrations.auto-migrate", false);
        if (!migrationsEnabled || !autoMigrate) {
            logger.info("PostgreSQL migrations are not configured for automatic runtime execution");
            return;
        }

        logger.info("Running PostgreSQL classpath migrations");
        new PostgresMigrationRunner(adapter).runClasspathMigrations(List.of(
            "migrations/001_create_minigame_tables.sql",
            "migrations/002_create_authority_core_tables.sql"
        ));
        logger.info("PostgreSQL migrations completed");
    }
    
    public void shutdown() {
        if (adapter != null) {
            try {
                adapter.close();
                logger.info("PostgreSQL authority backend disconnected successfully");
            } catch (Exception e) {
                logger.warning("Error disconnecting data storage: " + e.getMessage());
            }
        }
    }
}
