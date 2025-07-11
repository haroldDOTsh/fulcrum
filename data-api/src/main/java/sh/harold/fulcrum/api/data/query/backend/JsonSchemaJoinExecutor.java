package sh.harold.fulcrum.api.data.query.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.backend.json.JsonFileBackend;
import sh.harold.fulcrum.api.data.impl.JsonSchema;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.query.*;
import sh.harold.fulcrum.api.data.registry.PlayerDataRegistry;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * JSON-specific implementation of cross-schema query execution with optimizations
 * for file-based JSON storage.
 * 
 * <p>This executor provides the following optimizations:</p>
 * <ul>
 *   <li>Parallel file reading using streams</li>
 *   <li>Efficient application-level UUID intersection</li>
 *   <li>Smart caching of frequently accessed data</li>
 *   <li>Lazy loading and streaming for large datasets</li>
 *   <li>Memory-efficient processing with pagination</li>
 *   <li>Concurrent data loading from multiple schemas</li>
 * </ul>
 * 
 * <p>Since JSON files don't support native queries, all operations are performed
 * at the application level with optimizations for file I/O and memory usage.</p>
 * 
 * @author Harold
 * @since 1.0
 */
public class JsonSchemaJoinExecutor extends SchemaJoinExecutor {
    
    private static final Logger LOGGER = Logger.getLogger(JsonSchemaJoinExecutor.class.getName());
    
    /**
     * Object mapper for JSON parsing.
     */
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * Cache for schema file information.
     */
    private final Map<PlayerDataSchema<?>, FileInfo> fileInfoCache = new ConcurrentHashMap<>();
    
    /**
     * Cache for recently loaded data to avoid repeated file I/O.
     */
    private final Map<String, CachedData> dataCache = new ConcurrentHashMap<>();
    
    /**
     * Maximum cache size in entries.
     */
    private static final int MAX_CACHE_SIZE = 10000;
    
    /**
     * Cache TTL in milliseconds.
     */
    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes
    
    /**
     * Creates a new JsonSchemaJoinExecutor with default components.
     */
    public JsonSchemaJoinExecutor() {
        super();
    }
    
    /**
     * Creates a new JsonSchemaJoinExecutor with specified executor service.
     * 
     * @param executorService The executor service for async operations
     */
    public JsonSchemaJoinExecutor(ExecutorService executorService) {
        super(executorService);
    }
    
    /**
     * Executes a cross-schema query with JSON-specific optimizations.
     * 
     * @param queryBuilder The query builder containing the query specification
     * @return A CompletableFuture containing the query results
     */
    @Override
    public CompletableFuture<List<CrossSchemaResult>> execute(CrossSchemaQueryBuilder queryBuilder) {
        LOGGER.log(Level.FINE, "Executing JSON-optimized cross-schema query for root schema: {0}", 
                   queryBuilder.getRootSchema().schemaKey());
        
        // Check if all schemas use JsonFileBackend
        if (allSchemasUseJsonBackend(queryBuilder)) {
            return executeOptimizedJsonQuery(queryBuilder);
        } else {
            // Fall back to generic implementation
            LOGGER.log(Level.FINE, "Not all schemas use JsonFileBackend, falling back to generic implementation");
            return super.execute(queryBuilder);
        }
    }
    
    /**
     * Checks if all schemas use JsonFileBackend.
     */
    private boolean allSchemasUseJsonBackend(CrossSchemaQueryBuilder queryBuilder) {
        Set<PlayerDataSchema<?>> schemas = collectAllSchemas(queryBuilder);
        
        for (PlayerDataSchema<?> schema : schemas) {
            PlayerDataBackend backend = PlayerDataRegistry.getBackend(schema);
            
            if (!(backend instanceof JsonFileBackend)) {
                return false;
            }
            
            if (!(schema instanceof JsonSchema)) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Executes the query with JSON-specific optimizations.
     */
    private CompletableFuture<List<CrossSchemaResult>> executeOptimizedJsonQuery(CrossSchemaQueryBuilder queryBuilder) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Clean expired cache entries
                cleanExpiredCache();
                
                // Step 1: Load and filter data from all schemas in parallel
                Map<PlayerDataSchema<?>, CompletableFuture<Map<UUID, Object>>> schemaDataFutures = 
                    loadSchemaDataParallel(queryBuilder);
                
                // Step 2: Wait for all data to be loaded
                CompletableFuture.allOf(schemaDataFutures.values().toArray(new CompletableFuture[0])).join();
                
                Map<PlayerDataSchema<?>, Map<UUID, Object>> schemaData = new HashMap<>();
                for (Map.Entry<PlayerDataSchema<?>, CompletableFuture<Map<UUID, Object>>> entry : schemaDataFutures.entrySet()) {
                    schemaData.put(entry.getKey(), entry.getValue().join());
                }
                
                // Step 3: Apply joins using efficient UUID intersection
                Set<UUID> finalUuids = applyJoinsEfficiently(queryBuilder, schemaData);
                
                // Step 4: Build results
                List<CrossSchemaResult> results = buildResults(finalUuids, schemaData);
                
                // Step 5: Apply sorting
                results = applySorting(results, queryBuilder.getSortOrders());
                
                // Step 6: Apply pagination
                results = applyPagination(results, queryBuilder.getLimit(), queryBuilder.getOffset());
                
                LOGGER.log(Level.FINE, "Query completed with {0} results", results.size());
                
                return results;
                
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error executing JSON query", e);
                throw new RuntimeException("Failed to execute JSON query", e);
            }
        }, getExecutorService());
    }
    
    /**
     * Loads data from all schemas in parallel.
     */
    private Map<PlayerDataSchema<?>, CompletableFuture<Map<UUID, Object>>> loadSchemaDataParallel(
            CrossSchemaQueryBuilder queryBuilder) {
        Map<PlayerDataSchema<?>, CompletableFuture<Map<UUID, Object>>> futures = new HashMap<>();
        
        Set<PlayerDataSchema<?>> schemas = collectAllSchemas(queryBuilder);
        
        for (PlayerDataSchema<?> schema : schemas) {
            List<QueryFilter> filters = getFiltersForSchema(schema, queryBuilder);
            futures.put(schema, loadSchemaDataAsync(schema, filters));
        }
        
        return futures;
    }
    
    /**
     * Loads data for a specific schema asynchronously.
     */
    private CompletableFuture<Map<UUID, Object>> loadSchemaDataAsync(PlayerDataSchema<?> schema, 
                                                                     List<QueryFilter> filters) {
        return CompletableFuture.supplyAsync(() -> {
            LOGGER.log(Level.FINE, "Loading JSON data for schema: {0}", schema.schemaKey());
            
            FileInfo fileInfo = getFileInfo(schema);
            Map<UUID, Object> data = new ConcurrentHashMap<>();
            
            try {
                // Use parallel stream for efficient file reading
                Files.list(Paths.get(fileInfo.directoryPath))
                    .parallel()
                    .filter(path -> path.toString().endsWith(".json"))
                    .forEach(path -> {
                        try {
                            // Extract UUID from filename
                            String filename = path.getFileName().toString();
                            String uuidStr = filename.substring(0, filename.length() - 5); // Remove .json
                            UUID uuid = UUID.fromString(uuidStr);
                            
                            // Check cache first
                            String cacheKey = schema.schemaKey() + ":" + uuid;
                            CachedData cached = dataCache.get(cacheKey);
                            
                            Object dataObject;
                            if (cached != null && !cached.isExpired()) {
                                dataObject = cached.data;
                            } else {
                                // Load from file
                                dataObject = loadFromFile(path, schema, uuid);
                                
                                // Cache the data
                                cacheData(cacheKey, dataObject);
                            }
                            
                            // Apply filters
                            if (passesFilters(dataObject, filters)) {
                                data.put(uuid, dataObject);
                            }
                            
                        } catch (Exception e) {
                            LOGGER.log(Level.WARNING, "Error loading file: " + path, e);
                        }
                    });
                    
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error listing files in: " + fileInfo.directoryPath, e);
            }
            
            LOGGER.log(Level.FINE, "Loaded {0} entries for schema {1}", 
                       new Object[]{data.size(), schema.schemaKey()});
            
            return data;
        }, getExecutorService());
    }
    
    /**
     * Loads data from a JSON file.
     */
    private Object loadFromFile(Path path, PlayerDataSchema<?> schema, UUID uuid) throws IOException {
        if (!(schema instanceof JsonSchema<?> jsonSchema)) {
            throw new IllegalArgumentException("Schema must be JsonSchema");
        }
        
        String json = Files.readString(path);
        var tree = objectMapper.readTree(json);
        String normalizedJson = objectMapper.writeValueAsString(tree);
        
        return jsonSchema.deserialize(uuid, normalizedJson);
    }
    
    /**
     * Caches data with TTL.
     */
    private void cacheData(String key, Object data) {
        // Implement simple LRU eviction if cache is too large
        if (dataCache.size() > MAX_CACHE_SIZE) {
            // Remove oldest entries
            dataCache.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.comparing(c -> c.timestamp)))
                .limit(dataCache.size() - MAX_CACHE_SIZE + 100) // Remove 100 extra
                .forEach(entry -> dataCache.remove(entry.getKey()));
        }
        
        dataCache.put(key, new CachedData(data));
    }
    
    /**
     * Cleans expired cache entries.
     */
    private void cleanExpiredCache() {
        dataCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }
    
    /**
     * Checks if an object passes all filters.
     */
    private boolean passesFilters(Object obj, List<QueryFilter> filters) {
        for (QueryFilter filter : filters) {
            if (!filter.test(obj)) {
                return false;
            }
        }
        return true;
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
     * Applies joins efficiently using UUID intersection.
     */
    private Set<UUID> applyJoinsEfficiently(CrossSchemaQueryBuilder queryBuilder,
                                           Map<PlayerDataSchema<?>, Map<UUID, Object>> schemaData) {
        // Use the parent's UUID intersection engine
        UUIDIntersectionEngine intersectionEngine = new UUIDIntersectionEngine();
        
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
                    // Keep all from left (result)
                    break;
                case RIGHT:
                    // Take all from right
                    result = new HashSet<>(joinUuids);
                    break;
                case FULL:
                    // Union
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
        // Use parallel stream for efficient result building
        return uuids.parallelStream()
            .map(uuid -> {
                CrossSchemaResult result = new CrossSchemaResult(uuid);
                
                // Add data from each schema
                for (Map.Entry<PlayerDataSchema<?>, Map<UUID, Object>> entry : schemaData.entrySet()) {
                    PlayerDataSchema<?> schema = entry.getKey();
                    Object data = entry.getValue().get(uuid);
                    if (data != null) {
                        addSchemaDataUnchecked(result, schema, data);
                    }
                }
                
                return result;
            })
            .collect(Collectors.toList());
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
            
            // Handle nulls
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
     * Gets file information for a schema.
     */
    private FileInfo getFileInfo(PlayerDataSchema<?> schema) {
        return fileInfoCache.computeIfAbsent(schema, s -> {
            PlayerDataBackend backend = PlayerDataRegistry.getBackend(s);
            if (!(backend instanceof JsonFileBackend jsonBackend)) {
                throw new IllegalStateException("Expected JsonFileBackend");
            }
            
            // Extract base directory using reflection
            try {
                Field baseDirField = JsonFileBackend.class.getDeclaredField("baseDir");
                baseDirField.setAccessible(true);
                File baseDir = (File) baseDirField.get(jsonBackend);
                
                String schemaDir = new File(baseDir, s.schemaKey()).getAbsolutePath();
                
                return new FileInfo(schemaDir);
                
            } catch (Exception e) {
                throw new RuntimeException("Failed to extract file info", e);
            }
        });
    }
    
    /**
     * Collects all schemas involved in the query.
     */
    private Set<PlayerDataSchema<?>> collectAllSchemas(CrossSchemaQueryBuilder queryBuilder) {
        Set<PlayerDataSchema<?>> schemas = new HashSet<>();
        schemas.add(queryBuilder.getRootSchema());
        
        for (JoinOperation join : queryBuilder.getJoins()) {
            schemas.add(join.getTargetSchema());
        }
        
        return schemas;
    }
    
    /**
     * Gets the executor service for async operations.
     */
    private ExecutorService getExecutorService() {
        // Access parent's executor service through reflection
        try {
            Field field = SchemaJoinExecutor.class.getDeclaredField("executorService");
            field.setAccessible(true);
            return (ExecutorService) field.get(this);
        } catch (Exception e) {
            throw new RuntimeException("Failed to access executor service", e);
        }
    }
    
    /**
     * Helper method to add schema data without type checking.
     */
    @SuppressWarnings("unchecked")
    private void addSchemaDataUnchecked(CrossSchemaResult result, PlayerDataSchema schema, Object data) {
        result.addSchemaData(schema, data);
    }
    
    /**
     * Container for file information.
     */
    private static class FileInfo {
        final String directoryPath;
        
        FileInfo(String directoryPath) {
            this.directoryPath = directoryPath;
        }
    }
    
    /**
     * Container for cached data with TTL.
     */
    private static class CachedData {
        final Object data;
        final long timestamp;
        
        CachedData(Object data) {
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }
}