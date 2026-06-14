package sh.harold.fulcrum.api.data.impl.postgres;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public final class PostgresMigrationRunner {
    private static final char UTF8_BOM = '\uFEFF';
    private static final String MIGRATION_TABLE = "fulcrum_schema_migrations";

    private final PostgresConnectionAdapter connectionAdapter;

    public PostgresMigrationRunner(PostgresConnectionAdapter connectionAdapter) {
        this.connectionAdapter = Objects.requireNonNull(connectionAdapter, "connectionAdapter");
    }

    public void runClasspathMigrations(List<String> resourcePaths) {
        if (resourcePaths == null || resourcePaths.isEmpty()) {
            return;
        }

        try (Connection connection = connectionAdapter.getConnection()) {
            ensureMigrationTable(connection);
            for (String resourcePath : resourcePaths) {
                applyMigration(connection, resourcePath);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to run PostgreSQL migrations", exception);
        }
    }

    private void ensureMigrationTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS fulcrum_schema_migrations (
                    version TEXT PRIMARY KEY,
                    resource_path TEXT NOT NULL,
                    checksum TEXT NOT NULL,
                    applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        }
    }

    private void applyMigration(Connection connection, String resourcePath) throws Exception {
        String sql = readResource(resourcePath);
        String version = version(resourcePath);
        String checksum = checksum(sql);

        String existingChecksum = existingChecksum(connection, version);
        if (existingChecksum != null) {
            if (!existingChecksum.equals(checksum)) {
                throw new IllegalStateException("Migration checksum changed for " + resourcePath);
            }
            return;
        }

        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement();
             PreparedStatement insert = connection.prepareStatement("""
                 INSERT INTO fulcrum_schema_migrations (version, resource_path, checksum)
                 VALUES (?, ?, ?)
                 """)) {
            statement.execute(sql);
            insert.setString(1, version);
            insert.setString(2, resourcePath);
            insert.setString(3, checksum);
            insert.executeUpdate();
            connection.commit();
        } catch (Exception exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private String existingChecksum(Connection connection, String version) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT checksum FROM " + MIGRATION_TABLE + " WHERE version = ?")) {
            statement.setString(1, version);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("checksum") : null;
            }
        }
    }

    private String readResource(String resourcePath) throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalArgumentException("Migration resource not found: " + resourcePath);
            }
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return sql.isEmpty() || sql.charAt(0) != UTF8_BOM ? sql : sql.substring(1);
        }
    }

    private String version(String resourcePath) {
        int slash = resourcePath.lastIndexOf('/');
        String fileName = slash >= 0 ? resourcePath.substring(slash + 1) : resourcePath;
        int underscore = fileName.indexOf('_');
        int dot = fileName.indexOf('.');
        if (underscore > 0) {
            return fileName.substring(0, underscore);
        }
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private String checksum(String sql) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(sql.getBytes(StandardCharsets.UTF_8)));
    }
}
