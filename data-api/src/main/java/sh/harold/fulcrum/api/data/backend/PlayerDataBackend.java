package sh.harold.fulcrum.api.data.backend;

import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.query.CrossSchemaQueryBuilder;
import sh.harold.fulcrum.api.data.query.CrossSchemaResult;
import sh.harold.fulcrum.api.data.query.QueryFilter;
import sh.harold.fulcrum.api.data.integration.QueryBuilderFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Core interface for player data storage backends.
 * This interface defines the contract for storing and retrieving player data
 * across different storage implementations (SQL, MongoDB, JSON files, etc).
 *
 * <p>Enhanced with query builder support for cross-schema queries while
 * maintaining backwards compatibility with existing implementations.</p>
 */
public interface PlayerDataBackend {

    /**
     * Queries data from the backend using filters and pagination.
     * Backends can override this method to provide optimized query support.
     *
     * @param schema The schema to query
     * @param filters The filters to apply
     * @param limit The maximum number of results to return (optional)
     * @param offset The starting index for pagination (optional)
     * @return A map of UUIDs to data objects matching the query
     */
    default Map<UUID, Object> query(PlayerDataSchema<?> schema, List<QueryFilter> filters, Optional<Integer> limit, Optional<Integer> offset) {
        throw new UnsupportedOperationException("Query method not implemented for this backend.");
    }
    <T> T load(UUID uuid, PlayerDataSchema<T> schema);

    <T> void save(UUID uuid, PlayerDataSchema<T> schema, T data);

    <T> T loadOrCreate(UUID uuid, PlayerDataSchema<T> schema);

    /**
     * Saves multiple data entries in a batch operation for better performance.
     * This method is optimized for bulk operations and dirty data persistence.
     *
     * @param entries A map of UUID to schema-data pairs to save
     * @return The number of entries successfully saved
     */
    default int saveBatch(Map<UUID, Map<PlayerDataSchema<?>, Object>> entries) {
        int savedCount = 0;
        for (Map.Entry<UUID, Map<PlayerDataSchema<?>, Object>> playerEntry : entries.entrySet()) {
            UUID playerId = playerEntry.getKey();
            for (Map.Entry<PlayerDataSchema<?>, Object> schemaEntry : playerEntry.getValue().entrySet()) {
                try {
                    @SuppressWarnings("unchecked")
                    PlayerDataSchema<Object> schema = (PlayerDataSchema<Object>) schemaEntry.getKey();
                    save(playerId, schema, schemaEntry.getValue());
                    savedCount++;
                } catch (Exception e) {
                    // Log the error but continue with other entries
                    System.err.println("Failed to save data for player " + playerId + ", schema " + schemaEntry.getKey().schemaKey() + ": " + e.getMessage());
                }
            }
        }
        return savedCount;
    }

    /**
     * Saves only specific changed fields for a player data entry.
     * This method is used for optimized dirty data persistence when only certain fields have changed.
     *
     * @param uuid          The player UUID
     * @param schema        The data schema
     * @param data          The complete data object
     * @param changedFields The specific fields that have changed (optional, can be null for full save)
     * @return true if save was successful, false otherwise
     */
    default <T> boolean saveChangedFields(UUID uuid, PlayerDataSchema<T> schema, T data, Collection<String> changedFields) {
        try {
            save(uuid, schema, data);
            return true;
        } catch (Exception e) {
            System.err.println("Failed to save changed fields for player " + uuid + ", schema " + schema.schemaKey() + ": " + e.getMessage());
            return false;
        }
    }
    
    // ========================
    // Query Builder Support
    // ========================
    
    /**
     * Creates a query builder for cross-schema queries.
     * This default implementation wraps the backend with query support.
     *
     * @param rootSchema The root schema to start the query from
     * @param <T> The type of the schema data
     * @return A new CrossSchemaQueryBuilder instance
     * @since 1.0.0
     */
    default <T> CrossSchemaQueryBuilder createQueryBuilder(PlayerDataSchema<T> rootSchema) {
        QueryBuilderFactory factory = new QueryBuilderFactory(this);
        return factory.createQueryBuilder(rootSchema);
    }
    
    /**
     * Creates a query builder for cross-schema queries using a schema class.
     * 
     * @param schemaClass The root schema class to start the query from
     * @param <T> The type of the schema data
     * @return A new CrossSchemaQueryBuilder instance
     * @since 1.0.0
     */
    default <T> CrossSchemaQueryBuilder createQueryBuilder(Class<? extends PlayerDataSchema<T>> schemaClass) {
        try {
            PlayerDataSchema<T> schema = schemaClass.getDeclaredConstructor().newInstance();
            return createQueryBuilder(schema);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate schema: " + schemaClass.getName(), e);
        }
    }
    
    /**
     * Executes a cross-schema query asynchronously.
     * 
     * @param query The query to execute
     * @return A CompletableFuture containing the query results
     * @since 1.0.0
     */
    default CompletableFuture<List<CrossSchemaResult>> executeQuery(CrossSchemaQueryBuilder query) {
        return query.executeAsync();
    }
    
    /**
     * Gets a query builder factory configured for this backend.
     * 
     * @return A QueryBuilderFactory instance
     * @since 1.0.0
     */
    default QueryBuilderFactory getQueryBuilderFactory() {
        return new QueryBuilderFactory(this);
    }
    
    /**
     * Checks if this backend supports native cross-schema queries.
     * Backends can override this to indicate they have optimized query support.
     * 
     * @return true if the backend has native query support
     * @since 1.0.0
     */
    default boolean supportsNativeQueries() {
        return false;
    }
    
    
    /**
     * Gets backend-specific metadata for optimization hints.
     * Backends can override this to provide information about their capabilities.
     * 
     * @return A map of metadata key-value pairs
     * @since 1.0.0
     */
    default Map<String, Object> getBackendMetadata() {
        return Map.of(
            "type", this.getClass().getSimpleName(),
            "supportsNativeQueries", supportsNativeQueries(),
            "supportsBatchOperations", true
        );
    }
}
