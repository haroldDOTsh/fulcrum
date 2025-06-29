package sh.harold.fulcrum.api.playerdata;

import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.Yaml;
import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.backend.json.JsonFileBackend;
import sh.harold.fulcrum.api.data.backend.mongo.MongoDataBackend;
import sh.harold.fulcrum.api.data.backend.sql.SqlDataBackend;
import sh.harold.fulcrum.api.data.backend.sql.SqlDialect;
import sh.harold.fulcrum.api.data.backend.sql.SqliteDialect;
import sh.harold.fulcrum.api.data.backend.sql.PostgresDialect;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

public final class StorageManager {

    private static PlayerDataBackend structuredBackend;
    private static PlayerDataBackend documentBackend;
    private static boolean initialized = false;

    private StorageManager() {
    }

    @SuppressWarnings("unchecked")
    public static synchronized void initialize(JavaPlugin plugin) {
        if (initialized) {
            throw new IllegalStateException("StorageManager already initialized");
        }

        Logger logger = plugin.getLogger();
        File configFile = saveDefaultConfig(plugin);

        Map<String, Object> config = null;
        try (FileInputStream fis = new FileInputStream(configFile)) {
            config = new Yaml().load(fis);
        } catch (IOException e) {
            logger.severe("Config file could not be read, using default backend settings. " + e.getMessage());
        }

        if (config == null) {
            config = Map.of();
        }

        Map<String, Object> backends = (Map<String, Object>) config.getOrDefault("backends", Map.of());
        String structuredType = Objects.toString(backends.getOrDefault("structured", "sqlite"));
        String documentType = Objects.toString(backends.getOrDefault("document", "json"));

        // Structured backend
        switch (structuredType.toLowerCase()) {
            case "sqlite" -> {
                Map<String, Object> sqlite = (Map<String, Object>) config.getOrDefault("sqlite", Map.of());
                String file = Objects.toString(sqlite.getOrDefault("file", "data/players.db"));
                File dbFile = new File(plugin.getDataFolder(), file);
                dbFile.getParentFile().mkdirs();

                try {
                    Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
                    SqlDialect dialect = new SqliteDialect();
                    // Set the global dialect for player-api
                    sh.harold.fulcrum.api.data.backend.sql.SqlDialectProvider.setDialect(dialect);
                    structuredBackend = new SqlDataBackend(conn, dialect);
                    logger.info("Using SQLite for structured data: " + dbFile.getAbsolutePath());
                } catch (Exception e) {
                    logger.severe("Failed to connect to SQLite: " + e.getMessage());
                    throw new RuntimeException(e);
                }
            }
            case "postgres" -> {
                Map<String, Object> pg = (Map<String, Object>) config.getOrDefault("postgres", Map.of());
                String host = Objects.toString(pg.getOrDefault("host", "localhost"));
                int port = Integer.parseInt(Objects.toString(pg.getOrDefault("port", "5432")));
                String db = Objects.toString(pg.getOrDefault("database", "players"));
                String user = Objects.toString(pg.getOrDefault("user", "dev"));
                String pw = Objects.toString(pg.getOrDefault("password", "password"));
                String url = "jdbc:postgresql://" + host + ":" + port + "/" + db;

                try {
                    Connection conn = DriverManager.getConnection(url, user, pw);
                    SqlDialect dialect = new PostgresDialect();
                    // Set the global dialect for player-api
                    sh.harold.fulcrum.api.data.backend.sql.SqlDialectProvider.setDialect(dialect);
                    structuredBackend = new SqlDataBackend(conn, dialect);
                    logger.info("Using Postgres for structured data: " + url);
                } catch (Exception e) {
                    logger.severe("Failed to connect to Postgres: " + e.getMessage());
                    throw new RuntimeException(e);
                }
            }
            default -> throw new IllegalArgumentException("Unknown structured backend: " + structuredType);
        }

        // Document backend
        switch (documentType.toLowerCase()) {
            case "json" -> {
                Map<String, Object> json = (Map<String, Object>) config.getOrDefault("json", Map.of());
                String dir = Objects.toString(json.getOrDefault("dir", "data/json/"));
                File jsonDir = new File(plugin.getDataFolder(), dir);
                jsonDir.mkdirs();

                documentBackend = new JsonFileBackend(jsonDir.getAbsolutePath());
                logger.info("Using JSON files for document data: " + jsonDir.getAbsolutePath());
            }
            case "mongo" -> {
                Map<String, Object> mongo = (Map<String, Object>) config.getOrDefault("mongo", Map.of());
                String uri = Objects.toString(mongo.getOrDefault("uri", "mongodb://localhost:27017"));
                String db = Objects.toString(mongo.getOrDefault("database", "players"));

                try {
                    var client = com.mongodb.client.MongoClients.create(uri);
                    var database = client.getDatabase(db);
                    var collection = database.getCollection("playerdata");
                    documentBackend = new MongoDataBackend(collection);
                    logger.info("Using MongoDB for document data: " + uri + ", db=" + db);
                } catch (Exception e) {
                    logger.severe("Failed to connect to MongoDB: " + e.getMessage());
                    throw new RuntimeException(e);
                }
            }
            default -> throw new IllegalArgumentException("Unknown document backend: " + documentType);
        }
        initialized = true;
    }

    private static File saveDefaultConfig(JavaPlugin plugin) {
        File configFile = new File(plugin.getDataFolder(), "database-config.yml");
        if (configFile.exists()) {
            return configFile;
        }

        plugin.getDataFolder().mkdirs();

        try (InputStream stream = plugin.getResource("database-config.yml")) {
            if (stream == null) {
                throw new IOException("Resource 'database-config.yml' not found in plugin JAR.");
            }
            Files.copy(stream, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().info("Created default database-config.yml");
        } catch (IOException e) {
            plugin.getLogger().severe("Could not create default database-config.yml: " + e.getMessage());
        }
        return configFile;
    }

    public static PlayerDataBackend getStructuredBackend() {
        if (!initialized) {
            throw new IllegalStateException("StorageManager not initialized");
        }
        return structuredBackend;
    }

    public static PlayerDataBackend getDocumentBackend() {
        if (!initialized) {
            throw new IllegalStateException("StorageManager not initialized");
        }
        return documentBackend;
    }
}
