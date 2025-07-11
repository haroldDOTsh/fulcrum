package sh.harold.fulcrum.api.data.query.backend;

import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.backend.json.JsonFileBackend;
import sh.harold.fulcrum.api.data.backend.mongo.MongoDataBackend;
import sh.harold.fulcrum.api.data.backend.sql.SqlDataBackend;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.query.*;
import sh.harold.fulcrum.api.data.registry.PlayerDataRegistry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Factory for creating backend-specific query executors based on the schemas
 * involved in a cross-schema query.
 * 
 * <p>This factory analyzes the query to determine the most appropriate executor:</p>
 * <ul>
 *   <li>{@link SqlSchemaJoinExecutor} when all schemas use SQL backends with same connection</li>
 *   <li>{@link MongoSchemaJoinExecutor} when all schemas use MongoDB backends in same database</li>
 *   <li>{@link JsonSchemaJoinExecutor} when all schemas use JSON file backends</li>
 *   <li>{@link SchemaJoinExecutor} for mixed backends or when specific optimizations aren't available</li>
 * </ul>
 * 
 * <p>The factory caches executors for reuse and provides configuration options
 * for tuning performance based on the deployment environment.</p>
 * 
 * @author Harold
 * @since 1.0
 */
public class BackendSpecificExecutorFactory {
    
    private static final Logger LOGGER = Logger.getLogger(BackendSpecificExecutorFactory.class.getName());
    
    /**
     * Singleton instance of the factory.
     */
    private static final BackendSpecificExecutorFactory INSTANCE = new BackendSpecificExecutorFactory();
    
    /**
     * Cache of executors by backend type.
     */
    private final Map<BackendType, SchemaJoinExecutor> executorCache = new ConcurrentHashMap<>();
    
    /**
     * Shared executor service for all executors.
     */
    private final ExecutorService executorService;
    
    /**
     * Configuration for the factory.
     */
    private final FactoryConfiguration configuration;
    
    /**
     * Enum representing different backend types.
     */
    public enum BackendType {
        SQL,
        MONGODB,
        JSON,
        MIXED
    }
    
    /**
     * Configuration class for the factory.
     */
    public static class FactoryConfiguration {
        private boolean enableCaching = true;
        private boolean preferNativeJoins = true;
        private ExecutorService customExecutorService = null;
        private int maxCachedExecutors = 10;
        
        public FactoryConfiguration enableCaching(boolean enable) {
            this.enableCaching = enable;
            return this;
        }
        
        public FactoryConfiguration preferNativeJoins(boolean prefer) {
            this.preferNativeJoins = prefer;
            return this;
        }
        
        public FactoryConfiguration withExecutorService(ExecutorService executorService) {
            this.customExecutorService = executorService;
            return this;
        }
        
        public FactoryConfiguration maxCachedExecutors(int max) {
            this.maxCachedExecutors = max;
            return this;
        }
    }
    
    /**
     * Private constructor for singleton pattern.
     */
    private BackendSpecificExecutorFactory() {
        this(new FactoryConfiguration());
    }
    
    /**
     * Constructor with configuration.
     */
    private BackendSpecificExecutorFactory(FactoryConfiguration configuration) {
        this.configuration = configuration;
        this.executorService = configuration.customExecutorService != null 
            ? configuration.customExecutorService 
            : ForkJoinPool.commonPool();
    }
    
    /**
     * Gets the singleton instance of the factory.
     * 
     * @return The factory instance
     */
    public static BackendSpecificExecutorFactory getInstance() {
        return INSTANCE;
    }
    
    /**
     * Creates a new factory instance with custom configuration.
     * 
     * @param configuration The configuration
     * @return A new factory instance
     */
    public static BackendSpecificExecutorFactory createWithConfiguration(FactoryConfiguration configuration) {
        return new BackendSpecificExecutorFactory(configuration);
    }
    
    /**
     * Creates an appropriate executor for the given query.
     * 
     * @param queryBuilder The query builder
     * @return A schema join executor optimized for the query's backends
     */
    public SchemaJoinExecutor createExecutor(CrossSchemaQueryBuilder queryBuilder) {
        BackendType backendType = analyzeBackends(queryBuilder);
        
        LOGGER.log(Level.FINE, "Creating executor for backend type: {0}", backendType);
        
        if (configuration.enableCaching) {
            return executorCache.computeIfAbsent(backendType, this::createExecutorForType);
        } else {
            return createExecutorForType(backendType);
        }
    }
    
    /**
     * Creates an executor without analyzing the query, using the specified backend type.
     * 
     * @param backendType The backend type to create an executor for
     * @return A schema join executor for the specified backend type
     */
    public SchemaJoinExecutor createExecutor(BackendType backendType) {
        LOGGER.log(Level.FINE, "Creating executor for specified backend type: {0}", backendType);
        
        if (configuration.enableCaching) {
            return executorCache.computeIfAbsent(backendType, this::createExecutorForType);
        } else {
            return createExecutorForType(backendType);
        }
    }
    
    /**
     * Analyzes the backends used in a query to determine the best executor type.
     */
    private BackendType analyzeBackends(CrossSchemaQueryBuilder queryBuilder) {
        Set<PlayerDataSchema<?>> schemas = collectAllSchemas(queryBuilder);
        
        if (schemas.isEmpty()) {
            return BackendType.MIXED;
        }
        
        // Collect backend types
        Set<Class<? extends PlayerDataBackend>> backendTypes = new HashSet<>();
        Map<Class<? extends PlayerDataBackend>, List<PlayerDataBackend>> backendInstances = new HashMap<>();
        
        for (PlayerDataSchema<?> schema : schemas) {
            PlayerDataBackend backend = PlayerDataRegistry.getBackend(schema);
            if (backend == null) {
                LOGGER.log(Level.WARNING, "No backend registered for schema: {0}", schema.schemaKey());
                return BackendType.MIXED;
            }
            
            Class<? extends PlayerDataBackend> backendClass = backend.getClass();
            backendTypes.add(backendClass);
            backendInstances.computeIfAbsent(backendClass, k -> new ArrayList<>()).add(backend);
        }
        
        // If multiple backend types, use mixed
        if (backendTypes.size() > 1) {
            LOGGER.log(Level.FINE, "Multiple backend types detected, using MIXED executor");
            return BackendType.MIXED;
        }
        
        // Single backend type - check for specific optimizations
        Class<? extends PlayerDataBackend> backendClass = backendTypes.iterator().next();
        
        if (SqlDataBackend.class.isAssignableFrom(backendClass)) {
            // Check if all SQL backends use the same connection
            if (configuration.preferNativeJoins && allSqlBackendsUseSameConnection(backendInstances.get(backendClass))) {
                return BackendType.SQL;
            }
        } else if (MongoDataBackend.class.isAssignableFrom(backendClass)) {
            // Check if all MongoDB backends use the same database
            if (configuration.preferNativeJoins && allMongoBackendsUseSameDatabase(backendInstances.get(backendClass))) {
                return BackendType.MONGODB;
            }
        } else if (JsonFileBackend.class.isAssignableFrom(backendClass)) {
            return BackendType.JSON;
        }
        
        return BackendType.MIXED;
    }
    
    /**
     * Creates an executor for the specified backend type.
     */
    private SchemaJoinExecutor createExecutorForType(BackendType backendType) {
        // Clean cache if it's too large
        if (executorCache.size() > configuration.maxCachedExecutors) {
            clearCache();
        }
        
        switch (backendType) {
            case SQL:
                LOGGER.log(Level.INFO, "Creating SQL-specific join executor");
                return new SqlSchemaJoinExecutor(executorService);
                
            case MONGODB:
                LOGGER.log(Level.INFO, "Creating MongoDB-specific join executor");
                return new MongoSchemaJoinExecutor(executorService);
                
            case JSON:
                LOGGER.log(Level.INFO, "Creating JSON-specific join executor");
                return new JsonSchemaJoinExecutor(executorService);
                
            case MIXED:
            default:
                LOGGER.log(Level.INFO, "Creating generic join executor for mixed backends");
                return new SchemaJoinExecutor(executorService);
        }
    }
    
    /**
     * Checks if all SQL backends use the same connection.
     */
    private boolean allSqlBackendsUseSameConnection(List<PlayerDataBackend> backends) {
        if (backends.isEmpty()) {
            return false;
        }
        
        SqlDataBackend first = (SqlDataBackend) backends.get(0);
        java.sql.Connection firstConnection = first.getConnection();
        
        for (int i = 1; i < backends.size(); i++) {
            SqlDataBackend backend = (SqlDataBackend) backends.get(i);
            if (backend.getConnection() != firstConnection) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Checks if all MongoDB backends use the same database.
     */
    private boolean allMongoBackendsUseSameDatabase(List<PlayerDataBackend> backends) {
        if (backends.isEmpty()) {
            return false;
        }
        
        // This would require reflection or additional methods to extract database info
        // For now, we'll be conservative and assume they might be different
        // In a real implementation, you'd check the database names
        
        try {
            // Use reflection to get collection info
            String firstDatabase = null;
            
            for (PlayerDataBackend backend : backends) {
                java.lang.reflect.Field collectionField = MongoDataBackend.class.getDeclaredField("collection");
                collectionField.setAccessible(true);
                com.mongodb.client.MongoCollection<?> collection = 
                    (com.mongodb.client.MongoCollection<?>) collectionField.get(backend);
                
                String database = collection.getNamespace().getDatabaseName();
                
                if (firstDatabase == null) {
                    firstDatabase = database;
                } else if (!firstDatabase.equals(database)) {
                    return false;
                }
            }
            
            return true;
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to check MongoDB database consistency", e);
            return false;
        }
    }
    
    /**
     * Collects all schemas involved in a query.
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
     * Clears the executor cache.
     */
    public void clearCache() {
        executorCache.clear();
        LOGGER.log(Level.INFO, "Cleared executor cache");
    }
    
    /**
     * Gets the current cache size.
     * 
     * @return The number of cached executors
     */
    public int getCacheSize() {
        return executorCache.size();
    }
    
    /**
     * Gets information about the factory's current state.
     * 
     * @return A map of state information
     */
    public Map<String, Object> getFactoryInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("cacheEnabled", configuration.enableCaching);
        info.put("preferNativeJoins", configuration.preferNativeJoins);
        info.put("cacheSize", executorCache.size());
        info.put("cachedTypes", new ArrayList<>(executorCache.keySet()));
        info.put("executorService", executorService.getClass().getSimpleName());
        return info;
    }
    
    /**
     * Shuts down the factory, releasing resources.
     */
    public void shutdown() {
        clearCache();
        if (configuration.customExecutorService == null) {
            // We created the executor service, so we should shut it down
            executorService.shutdown();
        }
        LOGGER.log(Level.INFO, "Factory shutdown complete");
    }
}