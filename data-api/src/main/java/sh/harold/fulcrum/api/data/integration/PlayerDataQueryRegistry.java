package sh.harold.fulcrum.api.data.integration;

import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.query.CrossSchemaQueryBuilder;
import sh.harold.fulcrum.api.data.registry.PlayerDataRegistry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Registry for managing query-enabled schemas and their relationships.
 * This registry extends the functionality of PlayerDataRegistry by adding
 * query builder support, schema metadata, and relationship tracking.
 * 
 * <p>Key features:</p>
 * <ul>
 *   <li>Registration of queryable schemas with metadata</li>
 *   <li>Schema relationship tracking for optimized joins</li>
 *   <li>Query builder creation for registered schemas</li>
 *   <li>Schema discovery and introspection</li>
 *   <li>Integration with existing PlayerDataRegistry</li>
 * </ul>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * // Register schemas with query capabilities
 * PlayerDataQueryRegistry.registerQueryableSchema(
 *     rankSchema, 
 *     SqlDataBackend.class,
 *     SchemaMetadata.builder()
 *         .indexedFields("rank", "level")
 *         .joinableWith(GuildSchema.class)
 *         .build()
 * );
 * 
 * // Create query builder
 * CrossSchemaQueryBuilder query = PlayerDataQueryRegistry
 *     .queryBuilder(RankSchema.class)
 *     .where("rank", equals("MVP++"))
 *     .join(GuildSchema.class)
 *     .executeAsync();
 * 
 * // Get all schemas that can join with a given schema
 * Set<Class<?>> joinableSchemas = PlayerDataQueryRegistry
 *     .getJoinableSchemas(RankSchema.class);
 * }</pre>
 * 
 * @author Harold
 * @since 1.0
 */
public final class PlayerDataQueryRegistry {
    
    private static final Logger LOGGER = Logger.getLogger(PlayerDataQueryRegistry.class.getName());
    
    /**
     * Map of schema classes to their metadata.
     */
    private static final Map<Class<? extends PlayerDataSchema<?>>, SchemaMetadata> schemaMetadata = new ConcurrentHashMap<>();
    
    /**
     * Map of schema classes to their backend types.
     */
    private static final Map<Class<? extends PlayerDataSchema<?>>, Class<? extends PlayerDataBackend>> schemaBackendTypes = new ConcurrentHashMap<>();
    
    /**
     * Map of schema relationships for optimized joins.
     */
    private static final Map<Class<? extends PlayerDataSchema<?>>, Set<Class<? extends PlayerDataSchema<?>>>> schemaRelationships = new ConcurrentHashMap<>();
    
    /**
     * Query builder factory instance.
     */
    private static QueryBuilderFactory queryBuilderFactory;
    
    
    // Private constructor to prevent instantiation
    private PlayerDataQueryRegistry() {
    }
    
    /**
     * Registers a schema as queryable with metadata.
     *
     * @param schema The schema to register
     * @param backendType The backend type for this schema
     * @param metadata The schema metadata
     * @param <T> The schema data type
     */
    public static <T> void registerQueryableSchema(
            PlayerDataSchema<T> schema,
            Class<? extends PlayerDataBackend> backendType,
            SchemaMetadata metadata) {
        
        Class<? extends PlayerDataSchema<?>> schemaClass = (Class<? extends PlayerDataSchema<?>>) schema.getClass();
        
        // Register in PlayerDataRegistry if not already registered
        if (PlayerDataRegistry.getBackend(schema) == null) {
            LOGGER.warning("Schema not registered in PlayerDataRegistry: " + schema.schemaKey());
        }
        
        // Store metadata
        schemaMetadata.put(schemaClass, metadata);
        schemaBackendTypes.put(schemaClass, backendType);
        
        // Store relationships
        if (!metadata.getJoinableSchemas().isEmpty()) {
            schemaRelationships.put(schemaClass, new HashSet<>(metadata.getJoinableSchemas()));
            
            // Add reverse relationships
            for (Class<? extends PlayerDataSchema<?>> joinableSchema : metadata.getJoinableSchemas()) {
                schemaRelationships.computeIfAbsent(joinableSchema, k -> new HashSet<>()).add(schemaClass);
            }
        }
        
        LOGGER.info("Registered queryable schema: " + schema.schemaKey() + " with backend type: " + backendType.getSimpleName());
    }
    
    /**
     * Registers a schema as queryable without metadata.
     *
     * @param schema The schema to register
     * @param <T> The schema data type
     */
    public static <T> void registerQueryableSchema(PlayerDataSchema<T> schema) {
        PlayerDataBackend backend = PlayerDataRegistry.getBackend(schema);
        if (backend == null) {
            throw new IllegalStateException("Schema must be registered in PlayerDataRegistry first: " + schema.schemaKey());
        }
        
        registerQueryableSchema(schema, backend.getClass(), SchemaMetadata.empty());
    }
    
    /**
     * Creates a query builder for the specified schema.
     *
     * @param schemaClass The schema class
     * @param <T> The schema data type
     * @return A new CrossSchemaQueryBuilder
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
     * Creates a query builder for the specified schema instance.
     *
     * @param schema The schema
     * @param <T> The schema data type
     * @return A new CrossSchemaQueryBuilder
     */
    public static <T> CrossSchemaQueryBuilder queryBuilder(PlayerDataSchema<T> schema) {
        PlayerDataBackend backend = PlayerDataRegistry.getBackend(schema);
        if (backend == null) {
            throw new IllegalStateException("Schema not registered: " + schema.schemaKey());
        }
        
        // Always create a new factory for the specific backend
        queryBuilderFactory = new QueryBuilderFactory(backend);
        
        return queryBuilderFactory.createQueryBuilder(schema);
    }
    
    /**
     * Creates a query builder starting from any registered schema.
     *
     * @return A new CrossSchemaQueryBuilder
     */
    @SuppressWarnings("unchecked")
    public static CrossSchemaQueryBuilder queryBuilder() {
        if (schemaMetadata.isEmpty()) {
            throw new IllegalStateException("No queryable schemas registered");
        }
        
        // Use the first registered schema as the starting point
        Class<? extends PlayerDataSchema<?>> firstSchema = schemaMetadata.keySet().iterator().next();
        // Cast to raw type to bypass generic type constraints
        return queryBuilder((Class) firstSchema);
    }
    
    /**
     * Gets all queryable schemas.
     *
     * @return Unmodifiable set of queryable schema classes
     */
    public static Set<Class<? extends PlayerDataSchema<?>>> getQueryableSchemas() {
        return Collections.unmodifiableSet(schemaMetadata.keySet());
    }
    
    /**
     * Gets schemas that can be joined with the specified schema.
     *
     * @param schemaClass The schema class
     * @return Set of joinable schema classes
     */
    public static Set<Class<? extends PlayerDataSchema<?>>> getJoinableSchemas(
            Class<? extends PlayerDataSchema<?>> schemaClass) {
        Set<Class<? extends PlayerDataSchema<?>>> joinable = schemaRelationships.get(schemaClass);
        return joinable != null ? Collections.unmodifiableSet(joinable) : Collections.emptySet();
    }
    
    /**
     * Gets metadata for a schema.
     *
     * @param schemaClass The schema class
     * @return The schema metadata, or null if not found
     */
    public static SchemaMetadata getSchemaMetadata(Class<? extends PlayerDataSchema<?>> schemaClass) {
        return schemaMetadata.get(schemaClass);
    }
    
    /**
     * Checks if a schema is queryable.
     *
     * @param schemaClass The schema class
     * @return true if the schema is registered as queryable
     */
    public static boolean isQueryable(Class<? extends PlayerDataSchema<?>> schemaClass) {
        return schemaMetadata.containsKey(schemaClass);
    }
    
    /**
     * Gets all schemas with a specific indexed field.
     *
     * @param fieldName The field name
     * @return Set of schema classes that have the field indexed
     */
    public static Set<Class<? extends PlayerDataSchema<?>>> getSchemasWithIndexedField(String fieldName) {
        return schemaMetadata.entrySet().stream()
            .filter(entry -> entry.getValue().getIndexedFields().contains(fieldName))
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    }
    
    /**
     * Gets all schemas matching a predicate.
     *
     * @param predicate The predicate to match
     * @return Set of matching schema classes
     */
    public static Set<Class<? extends PlayerDataSchema<?>>> findSchemas(
            Predicate<SchemaMetadata> predicate) {
        return schemaMetadata.entrySet().stream()
            .filter(entry -> predicate.test(entry.getValue()))
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    }
    
    
    /**
     * Clears all registered queryable schemas.
     */
    public static void clear() {
        schemaMetadata.clear();
        schemaBackendTypes.clear();
        schemaRelationships.clear();
        queryBuilderFactory = null;
        LOGGER.info("Cleared query registry");
    }
    
    /**
     * Auto-discovers and registers queryable schemas from PlayerDataRegistry.
     */
    public static void autoDiscover() {
        Collection<PlayerDataSchema<?>> allSchemas = PlayerDataRegistry.allSchemas();
        int registered = 0;
        
        for (PlayerDataSchema<?> schema : allSchemas) {
            PlayerDataBackend backend = PlayerDataRegistry.getBackend(schema);
            if (backend != null) {
                @SuppressWarnings("unchecked")
                Class<? extends PlayerDataSchema<?>> schemaClass = (Class<? extends PlayerDataSchema<?>>) schema.getClass();
                if (!isQueryable(schemaClass)) {
                    registerQueryableSchema(schema, backend.getClass(), SchemaMetadata.empty());
                    registered++;
                }
            }
        }
        
        LOGGER.info("Auto-discovered and registered " + registered + " queryable schemas");
    }
    
    /**
     * Gets statistics about the query registry.
     *
     * @return Map of statistics
     */
    public static Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalQueryableSchemas", schemaMetadata.size());
        stats.put("totalRelationships", schemaRelationships.values().stream()
            .mapToInt(Set::size)
            .sum());
        stats.put("schemasWithIndexes", schemaMetadata.values().stream()
            .filter(m -> !m.getIndexedFields().isEmpty())
            .count());
        
        // Backend distribution
        Map<Class<?>, Long> backendCounts = schemaBackendTypes.values().stream()
            .collect(Collectors.groupingBy(c -> c, Collectors.counting()));
        stats.put("backendDistribution", backendCounts);
        
        return stats;
    }
    
    /**
     * Metadata about a queryable schema.
     */
    public static class SchemaMetadata {
        private final Set<String> indexedFields;
        private final Set<Class<? extends PlayerDataSchema<?>>> joinableSchemas;
        private final Map<String, Object> customProperties;
        private final boolean cacheable;
        private final long cacheTimeToLive;
        
        private SchemaMetadata(Builder builder) {
            this.indexedFields = Collections.unmodifiableSet(new HashSet<>(builder.indexedFields));
            this.joinableSchemas = Collections.unmodifiableSet(new HashSet<>(builder.joinableSchemas));
            this.customProperties = Collections.unmodifiableMap(new HashMap<>(builder.customProperties));
            this.cacheable = builder.cacheable;
            this.cacheTimeToLive = builder.cacheTimeToLive;
        }
        
        public Set<String> getIndexedFields() {
            return indexedFields;
        }
        
        public Set<Class<? extends PlayerDataSchema<?>>> getJoinableSchemas() {
            return joinableSchemas;
        }
        
        public Map<String, Object> getCustomProperties() {
            return customProperties;
        }
        
        public boolean isCacheable() {
            return cacheable;
        }
        
        public long getCacheTimeToLive() {
            return cacheTimeToLive;
        }
        
        public Object getCustomProperty(String key) {
            return customProperties.get(key);
        }
        
        public static SchemaMetadata empty() {
            return new Builder().build();
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private final Set<String> indexedFields = new HashSet<>();
            private final Set<Class<? extends PlayerDataSchema<?>>> joinableSchemas = new HashSet<>();
            private final Map<String, Object> customProperties = new HashMap<>();
            private boolean cacheable = true;
            private long cacheTimeToLive = 300000; // 5 minutes default
            
            public Builder indexedField(String field) {
                indexedFields.add(field);
                return this;
            }
            
            public Builder indexedFields(String... fields) {
                indexedFields.addAll(Arrays.asList(fields));
                return this;
            }
            
            public Builder indexedFields(Collection<String> fields) {
                indexedFields.addAll(fields);
                return this;
            }
            
            public Builder joinableWith(Class<? extends PlayerDataSchema<?>> schemaClass) {
                joinableSchemas.add(schemaClass);
                return this;
            }
            
            @SafeVarargs
            public final Builder joinableWith(Class<? extends PlayerDataSchema<?>>... schemaClasses) {
                joinableSchemas.addAll(Arrays.asList(schemaClasses));
                return this;
            }
            
            public Builder customProperty(String key, Object value) {
                customProperties.put(key, value);
                return this;
            }
            
            public Builder cacheable(boolean cacheable) {
                this.cacheable = cacheable;
                return this;
            }
            
            public Builder cacheTimeToLive(long ttlMillis) {
                this.cacheTimeToLive = ttlMillis;
                return this;
            }
            
            public SchemaMetadata build() {
                return new SchemaMetadata(this);
            }
        }
    }
}