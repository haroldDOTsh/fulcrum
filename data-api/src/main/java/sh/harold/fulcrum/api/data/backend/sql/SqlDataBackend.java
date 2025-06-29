package sh.harold.fulcrum.api.data.backend.sql;

import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.backend.core.AutoTableSchema;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.impl.TableSchema;

import java.sql.Connection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * SQL backend for player data using TableSchema logic.
 */
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
        autoSchema.save(uuid, data);
        LOGGER.info("Saved SQL data for " + uuid + " with schema " + schema.schemaKey());
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
}

