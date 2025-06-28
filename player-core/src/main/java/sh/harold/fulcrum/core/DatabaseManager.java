package sh.harold.fulcrum.core;

import org.yaml.snakeyaml.Yaml;
import sh.harold.fulcrum.api.data.backend.sql.PostgresDialect;
import sh.harold.fulcrum.api.data.backend.sql.SqlDialect;
import sh.harold.fulcrum.api.data.backend.sql.SqliteDialect;

import java.io.File;
import java.io.FileReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Centralized database and dialect manager for Fulcrum plugins.
 * Handles config parsing, connection, dialect detection, and test overrides.
 */
public final class DatabaseManager {
    private static final ReentrantLock lock = new ReentrantLock();
    private static volatile Connection connection;
    private static volatile SqlDialect dialect;
    private static boolean testOverride = false;

    private DatabaseManager() {
    }

    public static void setupFromConfig(File dataFolder) {
        if (connection != null && !testOverride) return;
        lock.lock();
        try {
            if (connection != null && !testOverride) return;
            File configFile = new File(dataFolder, "database-config.yml");
            Map<String, Object> config = null;
            try (FileReader reader = new FileReader(configFile)) {
                config = new Yaml().load(reader);
            }
            String type = ((String) config.getOrDefault("type", "sqlite")).toLowerCase(Locale.ROOT);
            switch (type) {
                case "sqlite" -> {
                    String file = (String) config.getOrDefault("file", "data/playerdata.db");
                    connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                }
                case "postgres", "postgresql" -> {
                    String host = (String) config.getOrDefault("host", "localhost");
                    int port = (int) config.getOrDefault("port", 5432);
                    String db = (String) config.getOrDefault("database", "playerdata");
                    String user = (String) config.getOrDefault("username", "postgres");
                    String pass = (String) config.getOrDefault("password", "");
                    String url = "jdbc:postgresql://" + host + ":" + port + "/" + db;
                    connection = DriverManager.getConnection(url, user, pass);
                }
                case "mysql", "mariadb" -> {
                    String host = (String) config.getOrDefault("host", "localhost");
                    int port = (int) config.getOrDefault("port", 3306);
                    String db = (String) config.getOrDefault("database", "playerdata");
                    String user = (String) config.getOrDefault("username", "root");
                    String pass = (String) config.getOrDefault("password", "");
                    String url = "jdbc:mysql://" + host + ":" + port + "/" + db;
                    connection = DriverManager.getConnection(url, user, pass);
                }
                default -> throw new IllegalArgumentException("Unsupported database type: " + type);
            }
            String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
            dialect = switch (product) {
                case "sqlite" -> new SqliteDialect();
                case "postgresql" -> new PostgresDialect();
                // case "mysql", "mariadb" -> new MariaDbDialect(); // implement as needed
                default -> throw new IllegalStateException("Unsupported DB: " + product);
            };
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize database: " + e, e);
        } finally {
            lock.unlock();
        }
    }

    public static Connection getConnection() {
        if (connection == null) throw new IllegalStateException("DatabaseManager not initialized");
        return connection;
    }

    public static SqlDialect getDialect() {
        if (dialect == null) throw new IllegalStateException("DatabaseManager not initialized");
        return dialect;
    }

    /**
     * For test/dev only: inject a mock connection and dialect.
     */
    public static void useMockConnection(Connection conn, SqlDialect d) {
        lock.lock();
        try {
            connection = conn;
            dialect = d;
            testOverride = true;
        } finally {
            lock.unlock();
        }
    }
}
