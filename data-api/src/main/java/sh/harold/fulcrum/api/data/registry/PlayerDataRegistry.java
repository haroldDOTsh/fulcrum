package sh.harold.fulcrum.api.data.registry;

import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.impl.LifecycleAwareSchema;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.query.CrossSchemaQueryBuilder;
import sh.harold.fulcrum.api.data.integration.QueryBuilderFactory;
import sh.harold.fulcrum.api.data.integration.PlayerDataQueryRegistry;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class PlayerDataRegistry {
    private static final Map<PlayerDataSchema<?>, PlayerDataBackend> schemaBackends = new HashMap<>();

    private PlayerDataRegistry() {
    }

    public static <T> void registerSchema(PlayerDataSchema<T> schema, PlayerDataBackend backend) {
        schemaBackends.put(schema, backend);
        // Automatic SQL table creation and schema version enforcement
        if (schema instanceof sh.harold.fulcrum.api.data.backend.core.AutoTableSchema<?> autoSchema &&
                backend instanceof sh.harold.fulcrum.api.data.backend.sql.SqlDataBackend sqlBackend) {
            try {
                autoSchema.ensureTableAndVersion(sqlBackend.getConnection());
            } catch (Exception e) {
                throw new RuntimeException("Failed to ensure table and schema version for " + schema.schemaKey(), e);
            }
        }
    }

    public static <T> PlayerDataBackend getBackend(PlayerDataSchema<T> schema) {
        return schemaBackends.get(schema);
    }

    public static Collection<PlayerDataSchema<?>> allSchemas() {
        return Collections.unmodifiableCollection(schemaBackends.keySet());
    }

    public static void clear() {
        schemaBackends.clear();
    }

    public static void notifyJoin(UUID playerId) {
        for (var schema : allSchemas()) {
            if (schema instanceof LifecycleAwareSchema lifecycle) {
                lifecycle.onJoin(playerId);
            }
        }
    }

    public static void notifyQuit(UUID playerId) {
        for (var schema : allSchemas()) {
            if (schema instanceof LifecycleAwareSchema lifecycle) {
                lifecycle.onQuit(playerId);
            }
        }
    }
    
    // ========================
    // Query Builder Support
    // ========================
    
    /**
     * Creates a query builder for cross-schema queries starting from the specified schema.
     *
     * @param schema The root schema to start the query from
     * @param <T> The type of the schema data
     * @return A new CrossSchemaQueryBuilder instance
     * @since 2.0
     */
    public static <T> CrossSchemaQueryBuilder queryBuilder(PlayerDataSchema<T> schema) {
        PlayerDataBackend backend = getBackend(schema);
        if (backend == null) {
            throw new IllegalStateException("No backend registered for schema: " + schema.schemaKey());
        }
        return backend.createQueryBuilder(schema);
    }
    
    /**
     * Creates a query builder for cross-schema queries using a schema class.
     *
     * @param schemaClass The root schema class to start the query from
     * @param <T> The type of the schema data
     * @return A new CrossSchemaQueryBuilder instance
     * @since 2.0
     */
    public static <T> CrossSchemaQueryBuilder queryBuilder(Class<? extends PlayerDataSchema<T>> schemaClass) {
        try {
            PlayerDataSchema<T> schema = schemaClass.getDeclaredConstructor().newInstance();
            return queryBuilder(schema);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate schema: " + schemaClass.getName(), e);
        }
    }
    
    /**
     * Creates a query builder from any registered schema.
     * Uses the first available schema as the starting point.
     *
     * @return A new CrossSchemaQueryBuilder instance
     * @throws IllegalStateException if no schemas are registered
     * @since 2.0
     */
    public static CrossSchemaQueryBuilder queryBuilder() {
        if (schemaBackends.isEmpty()) {
            throw new IllegalStateException("No schemas registered in PlayerDataRegistry");
        }
        
        // Use the first registered schema
        PlayerDataSchema<?> firstSchema = schemaBackends.keySet().iterator().next();
        return queryBuilder(firstSchema);
    }
    
    /**
     * Gets schemas that can be queried together (share the same backend).
     *
     * @param schema The schema to find compatible schemas for
     * @return Set of schemas that can be joined with the given schema
     * @since 2.0
     */
    public static Set<PlayerDataSchema<?>> getQueryableSchemas(PlayerDataSchema<?> schema) {
        PlayerDataBackend targetBackend = getBackend(schema);
        if (targetBackend == null) {
            return Collections.emptySet();
        }
        
        Set<PlayerDataSchema<?>> queryableSchemas = new HashSet<>();
        for (Map.Entry<PlayerDataSchema<?>, PlayerDataBackend> entry : schemaBackends.entrySet()) {
            if (entry.getValue().equals(targetBackend)) {
                queryableSchemas.add(entry.getKey());
            }
        }
        return queryableSchemas;
    }
    
    /**
     * Registers a schema as queryable with metadata.
     * This integrates with PlayerDataQueryRegistry for enhanced query capabilities.
     *
     * @param schema The schema to register
     * @param backend The backend for this schema
     * @param metadata The schema metadata
     * @param <T> The schema data type
     * @since 2.0
     */
    public static <T> void registerQueryableSchema(
            PlayerDataSchema<T> schema,
            PlayerDataBackend backend,
            PlayerDataQueryRegistry.SchemaMetadata metadata) {
        
        // Register in PlayerDataRegistry
        registerSchema(schema, backend);
        
        // Register in PlayerDataQueryRegistry
        PlayerDataQueryRegistry.registerQueryableSchema(schema, backend.getClass(), metadata);
    }
    
    /**
     * Auto-discovers and registers all schemas as queryable.
     * This enables query builder support for all registered schemas.
     *
     * @since 2.0
     */
    public static void enableQuerySupport() {
        PlayerDataQueryRegistry.autoDiscover();
    }
    
    /**
     * Gets statistics about registered schemas and their query capabilities.
     *
     * @return Map of statistics
     * @since 2.0
     */
    public static Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalSchemas", schemaBackends.size());
        
        // Group by backend type
        Map<String, Integer> backendCounts = new HashMap<>();
        for (PlayerDataBackend backend : schemaBackends.values()) {
            String backendType = backend.getClass().getSimpleName();
            backendCounts.merge(backendType, 1, Integer::sum);
        }
        stats.put("backendDistribution", backendCounts);
        
        // Query support statistics
        stats.put("queryStatistics", PlayerDataQueryRegistry.getStatistics());
        
        return stats;
    }
}
