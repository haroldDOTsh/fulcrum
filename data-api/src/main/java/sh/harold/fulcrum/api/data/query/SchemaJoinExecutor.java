package sh.harold.fulcrum.api.data.query;

import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.registry.PlayerDataRegistry;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Executes cross-schema queries by coordinating data retrieval from multiple backends
 * and applying joins, filters, and sorting.
 * 
 * <p>This executor handles the complex process of:</p>
 * <ul>
 *   <li>Loading data from multiple schemas through their respective backends</li>
 *   <li>Applying filters at the backend level when possible</li>
 *   <li>Performing UUID-based joins using the UUIDIntersectionEngine</li>
 *   <li>Aggregating results using the ResultAggregator</li>
 *   <li>Applying sorting and pagination</li>
 * </ul>
 * 
 * <p>All operations are performed asynchronously for optimal performance.</p>
 * 
 * @author Harold
 * @since 1.0
 */
public class SchemaJoinExecutor {
    
    private static final Logger LOGGER = Logger.getLogger(SchemaJoinExecutor.class.getName());
    
    private final ExecutorService executorService;
    private final UUIDIntersectionEngine intersectionEngine;
    private final ResultAggregator resultAggregator;
    
    /**
     * Cache for schema data to avoid redundant backend calls.
     */
    private final Map<String, CompletableFuture<Map<UUID, Object>>> schemaDataCache;
    
    /**
     * Creates a new SchemaJoinExecutor with default components.
     */
    public SchemaJoinExecutor() {
        this(ForkJoinPool.commonPool());
    }
    
    /**
     * Creates a new SchemaJoinExecutor with specified executor service.
     * 
     * @param executorService The executor service for async operations
     */
    public SchemaJoinExecutor(ExecutorService executorService) {
        this.executorService = Objects.requireNonNull(executorService, "Executor service cannot be null");
        this.intersectionEngine = new UUIDIntersectionEngine();
        this.resultAggregator = new ResultAggregator();
        this.schemaDataCache = new ConcurrentHashMap<>();
    }
    
    /**
     * Executes a cross-schema query and returns the results.
     * 
     * @param queryBuilder The query builder containing the query specification
     * @return A CompletableFuture containing the query results
     */
    public CompletableFuture<List<CrossSchemaResult>> execute(CrossSchemaQueryBuilder queryBuilder) {
        LOGGER.log(Level.FINE, "Executing cross-schema query for root schema: {0}", 
                   queryBuilder.getRootSchema().schemaKey());
        
        // Step 1: Collect all schemas involved in the query
        Set<PlayerDataSchema<?>> schemas = collectSchemas(queryBuilder);
        
        // Step 2: Load data from all schemas in parallel
        Map<PlayerDataSchema<?>, CompletableFuture<Map<UUID, Object>>> schemaDataFutures = new HashMap<>();
        for (PlayerDataSchema<?> schema : schemas) {
            schemaDataFutures.put(schema, loadSchemaData(schema, queryBuilder));
        }
        
        // Step 3: Wait for all data to be loaded and apply joins
        return CompletableFuture.allOf(schemaDataFutures.values().toArray(new CompletableFuture[0]))
            .thenCompose(v -> {
                try {
                    // Get loaded data
                    Map<PlayerDataSchema<?>, Map<UUID, Object>> schemaData = new HashMap<>();
                    for (Map.Entry<PlayerDataSchema<?>, CompletableFuture<Map<UUID, Object>>> entry : schemaDataFutures.entrySet()) {
                        schemaData.put(entry.getKey(), entry.getValue().join());
                    }
                    
                    // Apply joins to determine final UUID set
                    Set<UUID> finalUuids = applyJoins(queryBuilder, schemaData);
                    
                    // Build results
                    List<CrossSchemaResult> results = buildResults(finalUuids, schemaData);
                    
                    // Apply sorting
                    results = applySorting(results, queryBuilder.getSortOrders());
                    
                    // Apply pagination
                    results = applyPagination(results, queryBuilder.getLimit(), queryBuilder.getOffset());
                    
                    return CompletableFuture.completedFuture(results);
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Error executing cross-schema query", e);
                    return CompletableFuture.failedFuture(e);
                }
            });
    }
    
    /**
     * Collects all schemas involved in the query.
     */
    private Set<PlayerDataSchema<?>> collectSchemas(CrossSchemaQueryBuilder queryBuilder) {
        Set<PlayerDataSchema<?>> schemas = new HashSet<>();
        schemas.add(queryBuilder.getRootSchema());
        
        for (JoinOperation join : queryBuilder.getJoins()) {
            schemas.add(join.getTargetSchema());
        }
        
        return schemas;
    }
    
    /**
     * Loads data for a specific schema, applying filters where possible.
     */
    @SuppressWarnings("unchecked")
    private CompletableFuture<Map<UUID, Object>> loadSchemaData(PlayerDataSchema<?> schema, 
                                                                 CrossSchemaQueryBuilder queryBuilder) {
        String cacheKey = schema.schemaKey() + ":" + System.identityHashCode(queryBuilder);
        
        return schemaDataCache.computeIfAbsent(cacheKey, k -> 
            CompletableFuture.supplyAsync(() -> {
                LOGGER.log(Level.FINE, "Loading data for schema: {0}", schema.schemaKey());
                
                PlayerDataBackend backend = PlayerDataRegistry.getBackend(schema);
                if (backend == null) {
                    LOGGER.log(Level.WARNING, "No backend registered for schema: {0}", schema.schemaKey());
                    return Collections.emptyMap();
                }
                
                // Get filters for this schema
                List<QueryFilter> schemaFilters = getFiltersForSchema(schema, queryBuilder);
                
                // For now, we'll load all data and filter in memory
                // In a real implementation, we'd push filters to the backend
                Map<UUID, Object> allData = loadAllDataFromBackend(backend, schema);
                
                // Apply filters
                if (!schemaFilters.isEmpty()) {
                    Map<UUID, Object> filteredData = new HashMap<>();
                    for (Map.Entry<UUID, Object> entry : allData.entrySet()) {
                        if (passesFilters(entry.getValue(), schemaFilters)) {
                            filteredData.put(entry.getKey(), entry.getValue());
                        }
                    }
                    return filteredData;
                }
                
                return allData;
            }, executorService)
        );
    }
    
    /**
     * Loads all data from a backend for a schema.
     * This is a simplified implementation - in production, we'd use more efficient methods.
     */
    @SuppressWarnings("unchecked")
    private Map<UUID, Object> loadAllDataFromBackend(PlayerDataBackend backend, PlayerDataSchema<?> schema) {
        // This is a placeholder - in a real implementation, we'd need a method to load all data
        // or use a more sophisticated query mechanism
        Map<UUID, Object> data = new HashMap<>();
        
        // For now, log that we would load data
        LOGGER.log(Level.INFO, "Would load all data for schema {0} from backend {1}", 
                   new Object[]{schema.schemaKey(), backend.getClass().getSimpleName()});
        
        // In a real implementation, this would query the backend
        // For example: backend.loadAll(schema) or similar
        
        return data;
    }
    
    /**
     * Gets filters that apply to a specific schema.
     */
    private List<QueryFilter> getFiltersForSchema(PlayerDataSchema<?> schema, CrossSchemaQueryBuilder queryBuilder) {
        List<QueryFilter> filters = new ArrayList<>();
        
        // Root schema filters
        if (schema.equals(queryBuilder.getRootSchema())) {
            filters.addAll(queryBuilder.getFilters().stream()
                .filter(f -> f.getSchema().equals(schema))
                .collect(Collectors.toList()));
        }
        
        // Join filters
        for (JoinOperation join : queryBuilder.getJoins()) {
            if (join.getTargetSchema().equals(schema)) {
                filters.addAll(join.getFilters());
            }
        }
        
        return filters;
    }
    
    /**
     * Checks if an object passes all filters.
     */
    @SuppressWarnings("unchecked")
    private boolean passesFilters(Object obj, List<QueryFilter> filters) {
        for (QueryFilter filter : filters) {
            if (!filter.test(obj)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Applies joins to determine the final set of UUIDs to include in results.
     */
    private Set<UUID> applyJoins(CrossSchemaQueryBuilder queryBuilder, 
                                 Map<PlayerDataSchema<?>, Map<UUID, Object>> schemaData) {
        // Start with UUIDs from the root schema
        Set<UUID> result = new HashSet<>(schemaData.get(queryBuilder.getRootSchema()).keySet());
        
        // Apply each join
        for (JoinOperation join : queryBuilder.getJoins()) {
            Set<UUID> joinUuids = schemaData.get(join.getTargetSchema()).keySet();
            
            switch (join.getJoinType()) {
                case INNER:
                    result = intersectionEngine.intersect(result, joinUuids);
                    break;
                case LEFT:
                    // For left join, we keep all from the left (result)
                    break;
                case RIGHT:
                    // For right join, we take all from the right
                    result = new HashSet<>(joinUuids);
                    break;
                case FULL:
                    // For full join, we take the union
                    result = intersectionEngine.union(result, joinUuids);
                    break;
            }
        }
        
        return result;
    }
    
    /**
     * Builds CrossSchemaResult objects for the final UUID set.
     */
    private List<CrossSchemaResult> buildResults(Set<UUID> uuids, 
                                                 Map<PlayerDataSchema<?>, Map<UUID, Object>> schemaData) {
        List<CrossSchemaResult> results = new ArrayList<>();
        
        for (UUID uuid : uuids) {
            CrossSchemaResult result = new CrossSchemaResult(uuid);
            
            // Add data from each schema
            for (Map.Entry<PlayerDataSchema<?>, Map<UUID, Object>> entry : schemaData.entrySet()) {
                PlayerDataSchema<?> schema = entry.getKey();
                Object data = entry.getValue().get(uuid);
                if (data != null) {
                    addSchemaDataUnchecked(result, schema, data);
                }
            }
            
            results.add(result);
        }
        
        return results;
    }
    
    /**
     * Applies sorting to the results.
     */
    private List<CrossSchemaResult> applySorting(List<CrossSchemaResult> results, List<SortOrder> sortOrders) {
        if (sortOrders.isEmpty()) {
            return results;
        }
        
        // Create a comparator chain
        Comparator<CrossSchemaResult> comparator = null;
        
        for (SortOrder sortOrder : sortOrders) {
            Comparator<CrossSchemaResult> fieldComparator = createFieldComparator(sortOrder);
            
            if (comparator == null) {
                comparator = fieldComparator;
            } else {
                comparator = comparator.thenComparing(fieldComparator);
            }
        }
        
        if (comparator != null) {
            results.sort(comparator);
        }
        
        return results;
    }
    
    /**
     * Creates a comparator for a specific field.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Comparator<CrossSchemaResult> createFieldComparator(SortOrder sortOrder) {
        return (r1, r2) -> {
            Object v1 = r1.getField(sortOrder.getSchema(), sortOrder.getFieldName());
            Object v2 = r2.getField(sortOrder.getSchema(), sortOrder.getFieldName());
            
            // Handle nulls based on null handling strategy
            if (v1 == null && v2 == null) return 0;
            if (v1 == null) {
                return sortOrder.getNullHandling() == SortOrder.NullHandling.NULLS_FIRST ? -1 : 1;
            }
            if (v2 == null) {
                return sortOrder.getNullHandling() == SortOrder.NullHandling.NULLS_FIRST ? 1 : -1;
            }
            
            // Compare values
            int cmp = 0;
            if (v1 instanceof Comparable && v2 instanceof Comparable) {
                cmp = ((Comparable) v1).compareTo(v2);
            } else {
                cmp = v1.toString().compareTo(v2.toString());
            }
            
            // Apply sort direction
            return sortOrder.getDirection() == SortOrder.Direction.ASC ? cmp : -cmp;
        };
    }
    
    /**
     * Applies pagination to the results.
     */
    private List<CrossSchemaResult> applyPagination(List<CrossSchemaResult> results, 
                                                    Optional<Integer> limit, 
                                                    Optional<Integer> offset) {
        int startIndex = offset.orElse(0);
        int endIndex = results.size();
        
        if (limit.isPresent()) {
            endIndex = Math.min(startIndex + limit.get(), results.size());
        }
        
        if (startIndex >= results.size()) {
            return Collections.emptyList();
        }
        
        return results.subList(startIndex, endIndex);
    }
    
    /**
     * Clears the schema data cache.
     */
    public void clearCache() {
        schemaDataCache.clear();
        intersectionEngine.clearCache();
    }
    
    /**
     * Helper method to add schema data without type checking.
     * This is needed because we're working with generic types.
     */
    @SuppressWarnings("unchecked")
    private void addSchemaDataUnchecked(CrossSchemaResult result, PlayerDataSchema schema, Object data) {
        result.addSchemaData(schema, data);
    }
}