package sh.harold.fulcrum.api.data.backend.sql;

import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.backend.core.AutoTableSchema;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.impl.TableSchema;
import sh.harold.fulcrum.api.data.query.QueryFilter;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SqlDataBackend implements PlayerDataBackend {

    @Override
    public Map<UUID, Object> query(PlayerDataSchema<?> schema, List<QueryFilter> filters, Optional<Integer> limit, Optional<Integer> offset) {
        Map<UUID, Object> result = new HashMap<>();
        String sql = SqlQueryTranslator.translate(schema, filters, limit, offset);
        
        if (sql == null) {
            // SQL translation failed due to non-SQL-compatible filters, use fallback
            StringBuilder filterInfo = new StringBuilder();
            for (QueryFilter filter : filters) {
                if (!filter.isSqlCompatible()) {
                    filterInfo.append(String.format("  - Field '%s': %s (uses custom predicate)\n",
                                                   filter.getFieldName(), filter.toString()));
                }
            }
            
            LOGGER.log(Level.WARNING, "SQL translation failed for schema {0}. Non-SQL-compatible filters found:\n{1}" +
                      "Using in-memory fallback which may have performance implications for large datasets.",
                      new Object[]{schema.schemaKey(), filterInfo.toString()});
            
            return executeInMemoryFallback(schema, filters, limit, offset);
        }
        
        try (var statement = connection.prepareStatement(sql)) {
            var rs = statement.executeQuery();
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                Object data = schema.deserialize(rs);
                result.put(uuid, data);
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to execute query for schema " + schema.schemaKey(), e);
        }
        return result;
    }
    private static final Logger LOGGER = Logger.getLogger(SqlDataBackend.class.getName());
    private final Connection connection;
    private final SqlDialect dialect;
    private final Map<Class<?>, AutoTableSchema<?>> schemaCache = new ConcurrentHashMap<>();

    public SqlDataBackend(Connection connection, SqlDialect dialect) {
        this.connection = connection;
        this.dialect = dialect;
        
        // Set the connection provider for AutoTableSchema instances
        sh.harold.fulcrum.api.data.backend.core.AutoTableSchema.setConnectionProvider(() -> {
            LOGGER.info("[DEBUG] Connection provider called, returning connection: " + (connection != null ? "available" : "null"));
            return connection;
        });
        
        LOGGER.info("[DEBUG] SqlDataBackend initialized with connection: " + (connection != null ? "available" : "null"));
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
        LOGGER.info("[DEBUG] SqlDataBackend.save called: uuid=" + uuid + ", schema=" + schema.schemaKey() + ", data=" + data.getClass().getSimpleName());
        
        AutoTableSchema<T> autoSchema = getAutoTableSchema(schema);
        boolean originalAutoCommit = true;
        
        try {
            // Check current autoCommit state and set to true if needed
            originalAutoCommit = connection.getAutoCommit();
            LOGGER.info("[DEBUG] Current autoCommit state: " + originalAutoCommit);
            if (!originalAutoCommit) {
                connection.setAutoCommit(true);
                LOGGER.info("[DEBUG] Temporarily enabled autoCommit for save operation");
            }
            
            LOGGER.info("[DEBUG] Calling autoSchema.save for uuid=" + uuid);
            autoSchema.save(uuid, data);
            LOGGER.info("[DEBUG] Successfully saved SQL data for " + uuid + " with schema " + schema.schemaKey());
            
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "[DEBUG] SQLException during save for " + uuid +
                    " with schema " + schema.schemaKey(), e);
            throw new RuntimeException("SQL save operation failed", e);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[DEBUG] Exception during save for " + uuid +
                    " with schema " + schema.schemaKey(), e);
            throw new RuntimeException("Save operation failed", e);
        } finally {
            try {
                // Restore original autoCommit state
                if (connection.getAutoCommit() != originalAutoCommit) {
                    connection.setAutoCommit(originalAutoCommit);
                    LOGGER.info("[DEBUG] Restored original autoCommit state: " + originalAutoCommit);
                }
            } catch (SQLException e) {
                LOGGER.log(Level.WARNING, "[DEBUG] Failed to restore autoCommit state to " + originalAutoCommit, e);
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

    /**
     * Fallback method for when SQL translation fails due to non-SQL-compatible filters.
     * This method loads all data from the database and applies filters in-memory using predicates.
     *
     * @param schema The schema to query
     * @param filters The filters to apply
     * @param limit Optional limit for results
     * @param offset Optional offset for results
     * @return Map of UUID to data objects that pass the filters
     */
    private Map<UUID, Object> executeInMemoryFallback(PlayerDataSchema<?> schema, List<QueryFilter> filters, Optional<Integer> limit, Optional<Integer> offset) {
        Map<UUID, Object> result = new HashMap<>();
        
        try {
            // Load all data from the database without any WHERE clause
            String sql = "SELECT * FROM " + schema.schemaKey();
            
            try (var statement = connection.prepareStatement(sql)) {
                var rs = statement.executeQuery();
                
                // Apply filters in-memory using predicates
                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    Object data = schema.deserialize(rs);
                    
                    // Test if the data passes all filters
                    if (passesAllFilters(data, filters)) {
                        result.put(uuid, data);
                    }
                }
            }
            
            // Apply pagination in-memory
            if (offset.isPresent() || limit.isPresent()) {
                result = applyPagination(result, limit, offset);
            }
            
            LOGGER.log(Level.INFO, "In-memory fallback completed for schema " + schema.schemaKey() +
                      ", found " + result.size() + " matching records");
            
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to execute fallback query for schema " + schema.schemaKey(), e);
        }
        
        return result;
    }
    
    /**
     * Tests if a data object passes all the given filters.
     *
     * @param data The data object to test
     * @param filters The list of filters to apply
     * @return true if the data passes all filters, false otherwise
     */
    private boolean passesAllFilters(Object data, List<QueryFilter> filters) {
        for (QueryFilter filter : filters) {
            if (!filter.test(data)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Applies pagination to the result set in-memory.
     *
     * @param data The data to paginate
     * @param limit Optional limit for results
     * @param offset Optional offset for results
     * @return Paginated result set
     */
    private Map<UUID, Object> applyPagination(Map<UUID, Object> data, Optional<Integer> limit, Optional<Integer> offset) {
        java.util.List<Map.Entry<UUID, Object>> entries = new java.util.ArrayList<>(data.entrySet());
        
        int startIndex = offset.orElse(0);
        int endIndex = limit.map(l -> Math.min(startIndex + l, entries.size())).orElse(entries.size());
        
        if (startIndex >= entries.size()) {
            return new HashMap<>();
        }
        
        Map<UUID, Object> paginatedResult = new HashMap<>();
        for (int i = startIndex; i < endIndex; i++) {
            Map.Entry<UUID, Object> entry = entries.get(i);
            paginatedResult.put(entry.getKey(), entry.getValue());
        }
        
        return paginatedResult;
    }

    /**
     * Indicates that SqlDataBackend supports native queries through SqlQueryTranslator.
     * This backend can execute optimized SQL queries with WHERE clauses, LIMIT, and OFFSET
     * support, making it suitable for efficient cross-schema query operations.
     *
     * @return true, as SqlDataBackend has native query capabilities
     */
    @Override
    public boolean supportsNativeQueries() {
        return true;
    }
}

