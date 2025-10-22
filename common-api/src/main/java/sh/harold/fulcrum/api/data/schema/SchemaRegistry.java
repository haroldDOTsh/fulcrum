package sh.harold.fulcrum.api.data.schema;

import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;

import java.sql.*;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Minimal registry that ensures a schema definition has been applied at least once.
 * If a schema definition changes, we log a warning so developers can apply manual migrations.
 */
public final class SchemaRegistry {

    private static final Logger LOGGER = Logger.getLogger(SchemaRegistry.class.getName());
    private static final String METADATA_TABLE = "dataapi_schema_metadata";

    private SchemaRegistry() {
    }

    public static void ensureSchema(PostgresConnectionAdapter adapter, SchemaDefinition definition) {
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(definition, "definition");

        try (Connection connection = adapter.getConnection()) {
            connection.setAutoCommit(false);
            ensureMetadataTable(connection);

            SchemaRecord record = findRecord(connection, definition.id());
            String checksum = definition.checksum();

            if (record != null) {
                if (!record.checksum().equals(checksum)) {
                    LOGGER.severe(() -> "Schema '" + definition.id() + "' has changed since it was applied on "
                            + record.appliedAt() + ". Please run manual migration before updating the checksum.");
                }
                connection.rollback();
                return;
            }

            executeSqlStatements(connection, definition.loadSql());
            insertRecord(connection, definition.id(), checksum);
            connection.commit();
            LOGGER.info(() -> "Installed schema '" + definition.id() + "'" + describe(definition));
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "Failed to ensure schema '" + definition.id() + "'", ex);
            throw new IllegalStateException("Failed to ensure schema '" + definition.id() + "'", ex);
        }
    }

    private static void ensureMetadataTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS %s (
                        schema_id VARCHAR(200) PRIMARY KEY,
                        checksum VARCHAR(128) NOT NULL,
                        applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )""".formatted(METADATA_TABLE));
        }
    }

    private static SchemaRecord findRecord(Connection connection, String schemaId) throws SQLException {
        String sql = "SELECT schema_id, checksum, applied_at FROM " + METADATA_TABLE + " WHERE schema_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schemaId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return new SchemaRecord(
                            resultSet.getString("schema_id"),
                            resultSet.getString("checksum"),
                            resultSet.getTimestamp("applied_at").toInstant()
                    );
                }
                return null;
            }
        }
    }

    private static void executeSqlStatements(Connection connection, String script) throws SQLException {
        List<String> statements = SqlStatementSplitter.split(script);
        for (String sql : statements) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }
    }

    private static void insertRecord(Connection connection, String schemaId, String checksum) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + METADATA_TABLE + " (schema_id, checksum) VALUES (?, ?)")) {
            statement.setString(1, schemaId);
            statement.setString(2, checksum);
            statement.executeUpdate();
        }
    }

    private static String describe(SchemaDefinition definition) {
        if (definition.description().isBlank()) {
            return "";
        }
        return " (" + definition.description() + ")";
    }

    private record SchemaRecord(String schemaId, String checksum, Instant appliedAt) {
    }
}
