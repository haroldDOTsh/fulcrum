package sh.harold.fulcrum.api.data.integration;

import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.query.CrossSchemaQueryBuilder;
import sh.harold.fulcrum.api.data.query.CrossSchemaResult;
import sh.harold.fulcrum.api.data.query.SchemaJoinExecutor;
import sh.harold.fulcrum.api.data.query.backend.BackendSpecificExecutorFactory;
import sh.harold.fulcrum.api.data.query.batch.BatchConfiguration;
import sh.harold.fulcrum.api.data.registry.PlayerDataRegistry;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Enhanced PlayerDataBackend implementation that adds cross-schema query support
 * to existing backends. This class acts as a decorator/wrapper around traditional
 * PlayerDataBackend implementations, adding query builder capabilities while
 * maintaining backward compatibility.
 * 
 * <p>Key features:</p>
 * <ul>
 *   <li>Query builder creation for cross-schema queries</li>
 *   <li>Asynchronous query execution</li>
 *   <li>Backend-specific optimizations</li>
 *   <li>Caching support for query results</li>
 *   <li>Batch query operations</li>
 * </ul>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * // Wrap an existing backend
 * PlayerDataBackend sqlBackend = new SqlDataBackend(connection, dialect);
 * CrossSchemaPlayerDataBackend queryBackend = new CrossSchemaPlayerDataBackend(sqlBackend);
 * 
 * // Use traditional backend methods
 * RankData rankData = queryBackend.load(playerId, rankSchema);
 * 
 * // Use query builder
 * List<CrossSchemaResult> results = queryBackend
 *     .createQueryBuilder()
 *     .from(RankSchema.class)
 *     .where("rank", equals("MVP++"))
 *     .executeAsync()
 *     .get();
 * }</pre>
 * 
 * @author Harold
 * @since 1.0
 */
public class CrossSchemaPlayerDataBackend implements PlayerDataBackend {
    
    private static final Logger LOGGER = Logger.getLogger(CrossSchemaPlayerDataBackend.class.getName());
    
    /**
     * The wrapped backend implementation.
     */
    private final PlayerDataBackend delegate;
    
    /**
     * Factory for creating query builders.
     */
    private final QueryBuilderFactory queryBuilderFactory;
    
    /**
     * Executor factory for backend-specific optimizations.
     */
    private final BackendSpecificExecutorFactory executorFactory;
    
    /**
     * Configuration for batch operations.
     */
    private BatchConfiguration batchConfiguration;
    
    /**
     * Cache for query results.
     */
    private final Map<String, CachedResult> queryCache;
    
    /**
     * Whether query caching is enabled.
     */
    private boolean cachingEnabled = true;
    
    /**
     * Default cache TTL in milliseconds.
     */
    private long defaultCacheTTL = 300000; // 5 minutes
    
    /**
     * Creates a new CrossSchemaPlayerDataBackend wrapping the specified backend.
     *
     * @param delegate The backend to wrap
     */
    public CrossSchemaPlayerDataBackend(PlayerDataBackend delegate) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate backend cannot be null");
        this.queryBuilderFactory = new QueryBuilderFactory(delegate);
        this.executorFactory = BackendSpecificExecutorFactory.getInstance();
        this.batchConfiguration = BatchConfiguration.defaultConfig();
        this.queryCache = new ConcurrentHashMap<>();
    }
    
    /**
     * Creates a new CrossSchemaPlayerDataBackend with custom configuration.
     *
     * @param delegate The backend to wrap
     * @param batchConfig Custom batch configuration
     */
    public CrossSchemaPlayerDataBackend(PlayerDataBackend delegate, BatchConfiguration batchConfig) {
        this(delegate);
        this.batchConfiguration = batchConfig;
    }
    
    // ========================
    // Delegated Methods
    // ========================
    
    @Override
    public <T> T load(UUID uuid, PlayerDataSchema<T> schema) {
        return delegate.load(uuid, schema);
    }
    
    @Override
    public <T> void save(UUID uuid, PlayerDataSchema<T> schema, T data) {
        delegate.save(uuid, schema, data);
        // Invalidate relevant cache entries
        invalidateCache(schema);
    }
    
    @Override
    public <T> T loadOrCreate(UUID uuid, PlayerDataSchema<T> schema) {
        return delegate.loadOrCreate(uuid, schema);
    }
    
    @Override
    public int saveBatch(Map<UUID, Map<PlayerDataSchema<?>, Object>> entries) {
        int result = delegate.saveBatch(entries);
        // Invalidate cache for affected schemas
        entries.values().stream()
            .flatMap(m -> m.keySet().stream())
            .distinct()
            .forEach(this::invalidateCache);
        return result;
    }
    
    @Override
    public <T> boolean saveChangedFields(UUID uuid, PlayerDataSchema<T> schema, T data, Collection<String> changedFields) {
        boolean result = delegate.saveChangedFields(uuid, schema, data, changedFields);
        if (result) {
            invalidateCache(schema);
        }
        return result;
    }
    
    // ========================
    // Query Builder Methods
    // ========================
    
    /**
     * Creates a new query builder for cross-schema queries.
     *
     * @return A new CrossSchemaQueryBuilder instance
     */
    public CrossSchemaQueryBuilder createQueryBuilder() {
        throw new UnsupportedOperationException("Please use createQueryBuilder(PlayerDataSchema) with a specific schema");
    }
    
    /**
     * Creates a new query builder starting from the specified schema.
     *
     * @param schema The root schema for the query
     * @param <T> The schema type
     * @return A new CrossSchemaQueryBuilder instance
     */
    public <T> CrossSchemaQueryBuilder createQueryBuilder(PlayerDataSchema<T> schema) {
        return queryBuilderFactory.createQueryBuilder(schema);
    }
    
    /**
     * Creates a new query builder starting from the specified schema class.
     *
     * @param schemaClass The root schema class for the query
     * @param <T> The schema type
     * @return A new CrossSchemaQueryBuilder instance
     */
    public <T> CrossSchemaQueryBuilder createQueryBuilder(Class<? extends PlayerDataSchema<T>> schemaClass) {
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
     */
    public CompletableFuture<List<CrossSchemaResult>> executeQuery(CrossSchemaQueryBuilder query) {
        // Check cache if enabled
        String cacheKey = generateCacheKey(query);
        if (cachingEnabled && cacheKey != null) {
            CachedResult cached = queryCache.get(cacheKey);
            if (cached != null && !cached.isExpired()) {
                LOGGER.fine("Query cache hit for key: " + cacheKey);
                return CompletableFuture.completedFuture(cached.getResults());
            }
        }
        
        // Execute query
        return query.executeAsync()
            .thenApply(results -> {
                // Cache results if enabled
                if (cachingEnabled && cacheKey != null) {
                    cacheResults(cacheKey, results);
                }
                return results;
            })
            .exceptionally(throwable -> {
                LOGGER.log(Level.SEVERE, "Query execution failed", throwable);
                return Collections.emptyList();
            });
    }
    
    /**
     * Gets the query builder factory used by this backend.
     *
     * @return The query builder factory
     */
    public QueryBuilderFactory getQueryBuilderFactory() {
        return queryBuilderFactory;
    }
    
    /**
     * Sets the batch configuration for query operations.
     *
     * @param configuration The batch configuration
     */
    public void setBatchConfiguration(BatchConfiguration configuration) {
        this.batchConfiguration = Objects.requireNonNull(configuration, "Configuration cannot be null");
    }
    
    /**
     * Gets the current batch configuration.
     *
     * @return The batch configuration
     */
    public BatchConfiguration getBatchConfiguration() {
        return batchConfiguration;
    }
    
    /**
     * Enables or disables query result caching.
     *
     * @param enabled Whether to enable caching
     */
    public void setCachingEnabled(boolean enabled) {
        this.cachingEnabled = enabled;
        if (!enabled) {
            clearCache();
        }
    }
    
    /**
     * Sets the default cache TTL for query results.
     *
     * @param ttlMillis The TTL in milliseconds
     */
    public void setDefaultCacheTTL(long ttlMillis) {
        this.defaultCacheTTL = ttlMillis;
    }
    
    /**
     * Clears the query cache.
     */
    public void clearCache() {
        queryCache.clear();
        LOGGER.info("Query cache cleared");
    }
    
    /**
     * Gets the wrapped backend implementation.
     *
     * @return The delegate backend
     */
    public PlayerDataBackend getDelegate() {
        return delegate;
    }
    
    /**
     * Wraps multiple backends to support cross-backend queries.
     *
     * @param backends Map of schema to backend
     * @return A multi-backend wrapper
     */
    public static CrossSchemaPlayerDataBackend createMultiBackend(Map<PlayerDataSchema<?>, PlayerDataBackend> backends) {
        return new MultiBackendWrapper(backends);
    }
    
    // ========================
    // Private Helper Methods
    // ========================
    
    /**
     * Generates a cache key for a query.
     */
    private String generateCacheKey(CrossSchemaQueryBuilder query) {
        try {
            // Simple cache key based on query structure
            StringBuilder keyBuilder = new StringBuilder();
            keyBuilder.append(query.getRootSchema().schemaKey());
            
            query.getJoins().forEach(join -> {
                keyBuilder.append(":").append(join.getTargetSchema().schemaKey());
            });
            
            query.getFilters().forEach(filter -> {
                keyBuilder.append(":").append(filter.getFieldName());
            });
            
            query.getSortOrders().forEach(sort -> {
                keyBuilder.append(":").append(sort.getFieldName()).append(sort.getDirection());
            });
            
            query.getLimit().ifPresent(limit -> keyBuilder.append(":L").append(limit));
            query.getOffset().ifPresent(offset -> keyBuilder.append(":O").append(offset));
            
            return keyBuilder.toString();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to generate cache key", e);
            return null;
        }
    }
    
    /**
     * Caches query results.
     */
    private void cacheResults(String key, List<CrossSchemaResult> results) {
        long expirationTime = System.currentTimeMillis() + defaultCacheTTL;
        queryCache.put(key, new CachedResult(results, expirationTime));
    }
    
    /**
     * Invalidates cache entries for a schema.
     */
    private void invalidateCache(PlayerDataSchema<?> schema) {
        if (!cachingEnabled) {
            return;
        }
        
        String schemaKey = schema.schemaKey();
        queryCache.entrySet().removeIf(entry -> entry.getKey().contains(schemaKey));
    }
    
    /**
     * Cached query result.
     */
    private static class CachedResult {
        private final List<CrossSchemaResult> results;
        private final long expirationTime;
        
        CachedResult(List<CrossSchemaResult> results, long expirationTime) {
            this.results = new ArrayList<>(results);
            this.expirationTime = expirationTime;
        }
        
        List<CrossSchemaResult> getResults() {
            return new ArrayList<>(results);
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() > expirationTime;
        }
    }
    
    /**
     * Multi-backend wrapper for cross-backend queries.
     */
    private static class MultiBackendWrapper extends CrossSchemaPlayerDataBackend {
        private final Map<PlayerDataSchema<?>, PlayerDataBackend> backends;
        
        MultiBackendWrapper(Map<PlayerDataSchema<?>, PlayerDataBackend> backends) {
            super(backends.values().iterator().next()); // Use first backend as default
            this.backends = new HashMap<>(backends);
        }
        
        @Override
        public <T> T load(UUID uuid, PlayerDataSchema<T> schema) {
            PlayerDataBackend backend = backends.get(schema);
            if (backend == null) {
                backend = PlayerDataRegistry.getBackend(schema);
            }
            return backend != null ? backend.load(uuid, schema) : null;
        }
        
        @Override
        public <T> void save(UUID uuid, PlayerDataSchema<T> schema, T data) {
            PlayerDataBackend backend = backends.get(schema);
            if (backend == null) {
                backend = PlayerDataRegistry.getBackend(schema);
            }
            if (backend != null) {
                backend.save(uuid, schema, data);
            }
        }
    }
}