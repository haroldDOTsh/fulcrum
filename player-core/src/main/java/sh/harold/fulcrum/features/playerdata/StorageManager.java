package sh.harold.fulcrum.features.playerdata;

import org.yaml.snakeyaml.Yaml;
import sh.harold.fulcrum.backend.PlayerDataBackend;
import sh.harold.fulcrum.backend.json.JsonFileBackend;
import sh.harold.fulcrum.backend.mongo.MongoDataBackend;
import sh.harold.fulcrum.backend.sql.SqlDataBackend;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.sql.Connection;
import java.sql.DriverManager;

public final class StorageManager {
    private static PlayerDataBackend structuredBackend;
    private static PlayerDataBackend documentBackend;
    private static boolean initialized = false;

    private StorageManager() {}

    @SuppressWarnings("unchecked")
    public static synchronized void initialize(File configFile) {
        if (initialized) throw new IllegalStateException("StorageManager already initialized");
        Map<String, Object> config = null;
        try (FileInputStream fis = new FileInputStream(configFile)) {
            Yaml yaml = new Yaml();
            config = yaml.load(fis);
        } catch (IOException e) {
            System.out.println("[StorageManager] Config missing or unreadable, using defaults.");
        }
        if (config == null) config = Map.of();
        Map<String, Object> backends = (Map<String, Object>) config.getOrDefault("backends", Map.of());
        String structuredType = Objects.toString(backends.getOrDefault("structured", "sqlite"));
        String documentType = Objects.toString(backends.getOrDefault("document", "json"));
        // Structured backend
        switch (structuredType.toLowerCase()) {
            case "sqlite" -> {
                Map<String, Object> sqlite = (Map<String, Object>) config.getOrDefault("sqlite", Map.of());
                String file = Objects.toString(sqlite.getOrDefault("file", "data/players.db"));
                try {
                    Connection conn = DriverManager.getConnection("jdbc:sqlite:" + file);
                    structuredBackend = new SqlDataBackend(conn);
                    System.out.println("[StorageManager] Using SQLite for structured data: " + file);
                } catch (Exception e) {
                    System.out.println("[StorageManager] Failed to connect to SQLite: " + e);
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
                    structuredBackend = new SqlDataBackend(conn);
                    System.out.println("[StorageManager] Using Postgres for structured data: " + url);
                } catch (Exception e) {
                    System.out.println("[StorageManager] Failed to connect to Postgres: " + e);
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
                documentBackend = new JsonFileBackend(dir);
                System.out.println("[StorageManager] Using JSON files for document data: " + dir);
            }
            case "mongo" -> {
                Map<String, Object> mongo = (Map<String, Object>) config.getOrDefault("mongo", Map.of());
                String uri = Objects.toString(mongo.getOrDefault("uri", "mongodb://localhost:27017"));
                String db = Objects.toString(mongo.getOrDefault("database", "players"));
                try {
                    var client = com.mongodb.client.MongoClients.create(uri);
                    var collection = client.getDatabase(db).getCollection("playerdata");
                    documentBackend = new MongoDataBackend(collection);
                    System.out.println("[StorageManager] Using MongoDB for document data: " + uri + ", db=" + db);
                } catch (Exception e) {
                    System.out.println("[StorageManager] Failed to connect to MongoDB: " + e);
                    throw new RuntimeException(e);
                }
            }
            default -> throw new IllegalArgumentException("Unknown document backend: " + documentType);
        }
        initialized = true;
    }

    public static PlayerDataBackend getStructuredBackend() {
        if (!initialized) throw new IllegalStateException("StorageManager not initialized");
        return structuredBackend;
    }

    public static PlayerDataBackend getDocumentBackend() {
        if (!initialized) throw new IllegalStateException("StorageManager not initialized");
        return documentBackend;
    }
}
