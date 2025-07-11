package sh.harold.fulcrum.api.data.integration;

import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.query.CrossSchemaQueryBuilder;
import sh.harold.fulcrum.api.data.query.CrossSchemaResult;
import sh.harold.fulcrum.api.data.query.QueryFilter;
import sh.harold.fulcrum.api.data.query.batch.BatchConfiguration;
import sh.harold.fulcrum.api.data.query.batch.BatchResult;
import sh.harold.fulcrum.api.data.query.streaming.PaginationSupport;
import sh.harold.fulcrum.api.data.registry.PlayerDataRegistry;
import sh.harold.fulcrum.api.data.registry.PlayerProfile;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages cross-schema query operations for player data.
 * This manager provides high-level query operations, caching, and performance optimizations
 * for executing complex cross-schema queries across different backend types.
 * 
 * <p>Key features:</p>
 * <ul>
 *   <li>Simplified query API for common operations</li>
 *   <li>Query result caching with TTL support</li>
 *   <li>Query execution statistics and monitoring</li>
 *   <li>Batch query operations</li>
 *   <li>Integration with PlayerProfile system</li>
 * </ul>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * PlayerDataQueryManager manager = PlayerDataQueryManager.getInstance();
 * 
 * // Find all MVP++ players in the Titans guild
 * List<PlayerProfile> mvpPlusPlayers = manager
 *     .findPlayers()
 *     .withRank("MVP++")
 *     .inGuild("Titans")
 *     .executeAsync()
 *     .get();
 * 
 * // Get paginated results
 * PaginationSupport.Page<CrossSchemaResult> page = manager
 *     .createQuery(RankSchema.class)
 *     .where("level", greaterThan(100))
 *     .getPage(0, 20)
 *     .get();
 * }</pre>
 * 
 * @author Harold
 * @since 1.0
 */
public class PlayerDataQueryManager {
    
    private static final Logger LOGGER = Logger.getLogger(PlayerDataQueryManager.class.getName());
    
    /**
     * Singleton instance.
     */
    private static final PlayerDataQueryManager INSTANCE = new PlayerDataQueryManager();
    
    /**
     * Query builder factory.
     */
    private final QueryBuilderFactory queryBuilderFactory;
    
    /**
     * Cache for query results.
     */
    private final Map<String, CachedQueryResult> queryCache;
    
    /**
     * Query execution statistics.
     */
    private final QueryStatistics statistics;
    
    /**
     * Default cache TTL in milliseconds.
     */
    private long defaultCacheTTL = 300000; // 5 minutes
    
    /**
     * Maximum cache size.
     */
    private int maxCacheSize = 1000;
    
    /**
     * Whether caching is enabled.
     */
    private boolean cachingEnabled = true;
    
    /**
     * Private constructor for singleton.
     */
    private PlayerDataQueryManager() {
        // Initialize with a dummy backend - will be replaced when queries are executed
        this.queryBuilderFactory = new QueryBuilderFactory(new DummyBackend());
        this.queryCache = new ConcurrentHashMap<>();
        this.statistics = new QueryStatistics();
    }
    
    /**
     * Gets the singleton instance.
     *
     * @return The manager instance
     */
    public static PlayerDataQueryManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * Creates a new query builder for the specified schema.
     *
     * @param schemaClass The schema class to query
     * @param <T> The schema type
     * @return A new query builder
     */
    public <T> CrossSchemaQueryBuilder createQuery(Class<? extends PlayerDataSchema<T>> schemaClass) {
        try {
            PlayerDataSchema<T> schema = schemaClass.getDeclaredConstructor().newInstance();
            PlayerDataBackend backend = PlayerDataRegistry.getBackend(schema);
            if (backend == null) {
                throw new IllegalStateException("No backend registered for schema: " + schema.schemaKey());
            }
            return new QueryBuilderFactory(backend).createQueryBuilder(schema);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create query for schema: " + schemaClass.getName(), e);
        }
    }
    
    /**
     * Creates a new query builder for the specified schema instance.
     *
     * @param schema The schema to query
     * @param <T> The schema type
     * @return A new query builder
     */
    public <T> CrossSchemaQueryBuilder createQuery(PlayerDataSchema<T> schema) {
        PlayerDataBackend backend = PlayerDataRegistry.getBackend(schema);
        if (backend == null) {
            throw new IllegalStateException("No backend registered for schema: " + schema.schemaKey());
        }
        return new QueryBuilderFactory(backend).createQueryBuilder(schema);
    }
    
    /**
     * Creates a player finder for common player queries.
     *
     * @return A new player finder
     */
    public PlayerFinder findPlayers() {
        return new PlayerFinder(this);
    }
    
    /**
     * Executes a query with caching support.
     *
     * @param queryBuilder The query to execute
     * @param cacheKey Optional cache key (null to skip caching)
     * @param cacheTTL Cache TTL in milliseconds (0 to use default)
     * @return A future containing the query results
     */
    public CompletableFuture<List<CrossSchemaResult>> executeQuery(
            CrossSchemaQueryBuilder queryBuilder, 
            String cacheKey, 
            long cacheTTL) {
        
        // Check cache first
        if (cachingEnabled && cacheKey != null) {
            CachedQueryResult cached = queryCache.get(cacheKey);
            if (cached != null && !cached.isExpired()) {
                statistics.recordCacheHit();
                return CompletableFuture.completedFuture(cached.getResults());
            }
        }
        
        // Execute query
        long startTime = System.currentTimeMillis();
        return queryBuilder.executeAsync()
            .thenApply(results -> {
                long executionTime = System.currentTimeMillis() - startTime;
                statistics.recordQueryExecution(executionTime, results.size());
                
                // Cache results
                if (cachingEnabled && cacheKey != null) {
                    long ttl = cacheTTL > 0 ? cacheTTL : defaultCacheTTL;
                    cacheQueryResult(cacheKey, results, ttl);
                }
                
                return results;
            })
            .exceptionally(throwable -> {
                statistics.recordQueryError();
                LOGGER.log(Level.SEVERE, "Query execution failed", throwable);
                throw new RuntimeException("Query execution failed", throwable);
            });
    }
    
    /**
     * Executes a batch of queries efficiently.
     *
     * @param queries The queries to execute
     * @return A future containing all query results
     */
    public CompletableFuture<Map<CrossSchemaQueryBuilder, List<CrossSchemaResult>>> executeBatch(
            List<CrossSchemaQueryBuilder> queries) {
        
        Map<CrossSchemaQueryBuilder, CompletableFuture<List<CrossSchemaResult>>> futures = new HashMap<>();
        
        for (CrossSchemaQueryBuilder query : queries) {
            futures.put(query, query.executeAsync());
        }
        
        return CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                Map<CrossSchemaQueryBuilder, List<CrossSchemaResult>> results = new HashMap<>();
                futures.forEach((query, future) -> {
                    try {
                        results.put(query, future.join());
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Batch query failed", e);
                        results.put(query, Collections.emptyList());
                    }
                });
                return results;
            });
    }
    
    /**
     * Gets all players matching the specified UUIDs across all schemas.
     *
     * @param playerIds The player UUIDs to fetch
     * @return A future containing player profiles
     */
    public CompletableFuture<List<PlayerProfile>> getPlayerProfiles(Collection<UUID> playerIds) {
        return CompletableFuture.supplyAsync(() -> {
            List<PlayerProfile> profiles = new ArrayList<>();
            for (UUID playerId : playerIds) {
                // This would typically load from cache or create new profile
                profiles.add(new PlayerProfile(playerId));
            }
            return profiles;
        });
    }
    
    /**
     * Clears the query cache.
     */
    public void clearCache() {
        queryCache.clear();
        LOGGER.info("Query cache cleared");
    }
    
    /**
     * Gets query execution statistics.
     *
     * @return The statistics
     */
    public QueryStatistics getStatistics() {
        return statistics;
    }
    
    /**
     * Sets whether caching is enabled.
     *
     * @param enabled Whether to enable caching
     */
    public void setCachingEnabled(boolean enabled) {
        this.cachingEnabled = enabled;
    }
    
    /**
     * Sets the default cache TTL.
     *
     * @param ttlMillis The TTL in milliseconds
     */
    public void setDefaultCacheTTL(long ttlMillis) {
        this.defaultCacheTTL = ttlMillis;
    }
    
    /**
     * Sets the maximum cache size.
     *
     * @param maxSize The maximum number of cached queries
     */
    public void setMaxCacheSize(int maxSize) {
        this.maxCacheSize = maxSize;
    }
    
    /**
     * Caches query results.
     */
    private void cacheQueryResult(String key, List<CrossSchemaResult> results, long ttl) {
        // Evict old entries if cache is too large
        if (queryCache.size() >= maxCacheSize) {
            evictOldestCacheEntries();
        }
        
        queryCache.put(key, new CachedQueryResult(results, System.currentTimeMillis() + ttl));
    }
    
    /**
     * Evicts the oldest cache entries.
     */
    private void evictOldestCacheEntries() {
        int entriesToRemove = queryCache.size() / 4; // Remove 25% of entries
        queryCache.entrySet().stream()
            .sorted(Map.Entry.comparingByValue())
            .limit(entriesToRemove)
            .map(Map.Entry::getKey)
            .forEach(queryCache::remove);
    }
    
    /**
     * Fluent interface for finding players with common criteria.
     */
    public static class PlayerFinder {
        private final PlayerDataQueryManager manager;
        private final Map<String, Predicate<?>> criteria = new HashMap<>();
        private PlayerDataSchema<?> primarySchema;
        
        private PlayerFinder(PlayerDataQueryManager manager) {
            this.manager = manager;
        }
        
        public PlayerFinder withRank(String rank) {
            criteria.put("rank", obj -> rank.equals(obj));
            return this;
        }
        
        public PlayerFinder withMinLevel(int minLevel) {
            criteria.put("level", obj -> obj instanceof Number && ((Number) obj).intValue() >= minLevel);
            return this;
        }
        
        public PlayerFinder inGuild(String guildName) {
            criteria.put("guild", obj -> guildName.equals(obj));
            return this;
        }
        
        public PlayerFinder online() {
            criteria.put("online", obj -> Boolean.TRUE.equals(obj));
            return this;
        }
        
        public PlayerFinder withCustomCriteria(String field, Predicate<?> predicate) {
            criteria.put(field, predicate);
            return this;
        }
        
        public PlayerFinder fromSchema(PlayerDataSchema<?> schema) {
            this.primarySchema = schema;
            return this;
        }
        
        public CompletableFuture<List<PlayerProfile>> executeAsync() {
            if (primarySchema == null) {
                // Default to a common schema - this would need to be determined based on criteria
                throw new IllegalStateException("No primary schema specified");
            }
            
            CrossSchemaQueryBuilder query = manager.createQuery(primarySchema);
            
            // Apply criteria
            criteria.forEach((field, predicate) -> query.where(field, predicate));
            
            return query.executeAsync()
                .thenCompose(results -> {
                    Set<UUID> playerIds = results.stream()
                        .map(CrossSchemaResult::getPlayerUuid)
                        .collect(Collectors.toSet());
                    return manager.getPlayerProfiles(playerIds);
                });
        }
    }
    
    /**
     * Cached query result with expiration.
     */
    private static class CachedQueryResult implements Comparable<CachedQueryResult> {
        private final List<CrossSchemaResult> results;
        private final long expirationTime;
        
        CachedQueryResult(List<CrossSchemaResult> results, long expirationTime) {
            this.results = new ArrayList<>(results);
            this.expirationTime = expirationTime;
        }
        
        List<CrossSchemaResult> getResults() {
            return new ArrayList<>(results);
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() > expirationTime;
        }
        
        @Override
        public int compareTo(CachedQueryResult other) {
            return Long.compare(this.expirationTime, other.expirationTime);
        }
    }
    
    /**
     * Query execution statistics.
     */
    public static class QueryStatistics {
        private long totalQueries;
        private long totalExecutionTime;
        private long totalResults;
        private long cacheHits;
        private long errors;
        
        public synchronized void recordQueryExecution(long executionTime, int resultCount) {
            totalQueries++;
            totalExecutionTime += executionTime;
            totalResults += resultCount;
        }
        
        public synchronized void recordCacheHit() {
            cacheHits++;
        }
        
        public synchronized void recordQueryError() {
            errors++;
        }
        
        public synchronized Map<String, Object> getStats() {
            Map<String, Object> stats = new HashMap<>();
            stats.put("totalQueries", totalQueries);
            stats.put("averageExecutionTime", totalQueries > 0 ? totalExecutionTime / totalQueries : 0);
            stats.put("totalResults", totalResults);
            stats.put("cacheHitRate", totalQueries > 0 ? (double) cacheHits / totalQueries : 0);
            stats.put("errorRate", totalQueries > 0 ? (double) errors / totalQueries : 0);
            return stats;
        }
    }
    
    /**
     * Dummy backend implementation for initialization.
     */
    private static class DummyBackend implements PlayerDataBackend {
        @Override
        public <T> T load(UUID uuid, PlayerDataSchema<T> schema) {
            return null;
        }
        
        @Override
        public <T> void save(UUID uuid, PlayerDataSchema<T> schema, T data) {
            // No-op
        }
        
        @Override
        public <T> T loadOrCreate(UUID uuid, PlayerDataSchema<T> schema) {
            return null;
        }
    }
}