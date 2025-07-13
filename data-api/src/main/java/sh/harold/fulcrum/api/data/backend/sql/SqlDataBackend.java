package sh.harold.fulcrum.api.data.backend.sql;

import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.backend.core.AutoTableSchema;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.impl.TableSchema;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SqlDataBackend implements PlayerDataBackend {
    private static final Logger LOGGER = Logger.getLogger(SqlDataBackend.class.getName());
    private final Connection connection;
    private final SqlDialect dialect;
    private final Map<Class<?>, AutoTableSchema<?>> schemaCache = new ConcurrentHashMap<>();

    public SqlDataBackend(Connection connection, SqlDialect dialect) {
        this.connection = connection;
        this.dialect = dialect;
    }

    @SuppressWarnings("unchecked")
    private <T> AutoTableSchema<T> getAutoTableSchema(PlayerDataSchema<T> schema) {
        if (!(schema instanceof TableSchema<?>)) {
            throw new IllegalArgumentException("Not a TableSchema: " + schema.type());
        }
        return (AutoTableSchema<T>) schemaCache.computeIfAbsent(schema.type(), k -> new AutoTableSchema<>(schema.type(), connection));
    }

    @Override
    public <T> T load(UUID uuid, PlayerDataSchema<T> schema) {
        AutoTableSchema<T> autoSchema = getAutoTableSchema(schema);
        return autoSchema.load(uuid);
    }

    @Override
    public <T> void save(UUID uuid, PlayerDataSchema<T> schema, T data) {
        AutoTableSchema<T> autoSchema = getAutoTableSchema(schema);
        boolean originalAutoCommit = true;
        
        try {
            // Check current autoCommit state and set to true if needed
            originalAutoCommit = connection.getAutoCommit();
            if (!originalAutoCommit) {
                connection.setAutoCommit(true);
                LOGGER.fine("Temporarily enabled autoCommit for save operation");
            }
            
            autoSchema.save(uuid, data);
            LOGGER.info("Saved SQL data for " + uuid + " with schema " + schema.schemaKey());
            
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to save SQL data for " + uuid +
                    " with schema " + schema.schemaKey(), e);
            throw new RuntimeException("SQL save operation failed", e);
        } finally {
            try {
                // Restore original autoCommit state
                if (connection.getAutoCommit() != originalAutoCommit) {
                    connection.setAutoCommit(originalAutoCommit);
                    LOGGER.fine("Restored original autoCommit state: " + originalAutoCommit);
                }
            } catch (SQLException e) {
                LOGGER.log(Level.WARNING, "Failed to restore autoCommit state to " + originalAutoCommit, e);
            }
        }
    }

    @Override
    public <T> T loadOrCreate(UUID uuid, PlayerDataSchema<T> schema) {
        AutoTableSchema<T> autoSchema = getAutoTableSchema(schema);
        T loadedData = autoSchema.load(uuid);
        if (loadedData != null) {
            return loadedData;
        }

        try {
            T newInstance = autoSchema.instantiate(); // Assuming instantiate is public or accessible
            autoSchema.save(uuid, newInstance);
            return newInstance;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create new instance for schema " + schema.type().getName(), e);
        }
    }

    public Connection getConnection() {
        return connection;
    }

    @Override
    public int saveBatch(Map<UUID, Map<PlayerDataSchema<?>, Object>> entries) {
        int savedCount = 0;
        boolean originalAutoCommit = true;

        try {
            // Use transaction for batch operations
            originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);

            for (Map.Entry<UUID, Map<PlayerDataSchema<?>, Object>> playerEntry : entries.entrySet()) {
                UUID playerId = playerEntry.getKey();

                for (Map.Entry<PlayerDataSchema<?>, Object> schemaEntry : playerEntry.getValue().entrySet()) {
                    try {
                        @SuppressWarnings("unchecked")
                        PlayerDataSchema<Object> schema = (PlayerDataSchema<Object>) schemaEntry.getKey();
                        save(playerId, schema, schemaEntry.getValue());
                        savedCount++;
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Failed to save batch entry for player " + playerId +
                                ", schema " + schemaEntry.getKey().schemaKey(), e);
                    }
                }
            }

            connection.commit();
            if (savedCount > 0) {
                LOGGER.log(Level.INFO, "Batch saved {0} entries to SQL database", savedCount);
            }

        } catch (SQLException e) {
            try {
                connection.rollback();
                LOGGER.log(Level.WARNING, "Batch save failed, rolled back transaction", e);
            } catch (SQLException rollbackEx) {
                LOGGER.log(Level.SEVERE, "Failed to rollback transaction", rollbackEx);
            }
            savedCount = 0;
        } finally {
            try {
                connection.setAutoCommit(originalAutoCommit);
            } catch (SQLException e) {
                LOGGER.log(Level.WARNING, "Failed to restore auto-commit mode", e);
            }
        }

        return savedCount;
    }

    @Override
    public <T> boolean saveChangedFields(UUID uuid, PlayerDataSchema<T> schema, T data, Collection<String> changedFields) {
        // we could potentially implement field-level updates, but for now we save the entire object
        // could be optimized in the future to generate UPDATE statements for only changed fields
        try {
            save(uuid, schema, data);
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to save changed fields for player " + uuid +
                    ", schema " + schema.schemaKey(), e);
            return false;
        }
    }
}

