package sh.harold.fulcrum.backend.sql;

import sh.harold.fulcrum.api.PlayerDataSchema;
import sh.harold.fulcrum.api.TableSchema;
import sh.harold.fulcrum.backend.PlayerDataBackend;

import java.sql.Connection;
import java.util.UUID;

/**
 * SQL backend for player data using TableSchema logic.
 */
public class SqlDataBackend implements PlayerDataBackend {
    private final Connection connection;

    public SqlDataBackend(Connection connection) {
        this.connection = connection;
    }

    @Override
    public <T> T load(UUID uuid, PlayerDataSchema<T> schema) {
        if (!(schema instanceof TableSchema<?>))
            throw new IllegalArgumentException("Not a TableSchema: " + schema.type());
        // Implement actual SQL load logic here using connection and dialect
        throw new UnsupportedOperationException("Implement SQL load logic here");
    }

    @Override
    public <T> void save(UUID uuid, PlayerDataSchema<T> schema, T data) {
        if (!(schema instanceof TableSchema<?>))
            throw new IllegalArgumentException("Not a TableSchema: " + schema.type());
        // Implement actual SQL save logic here using connection and dialect
        throw new UnsupportedOperationException("Implement SQL save logic here");
    }
}
