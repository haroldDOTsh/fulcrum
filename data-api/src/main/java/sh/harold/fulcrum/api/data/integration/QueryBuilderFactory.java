package sh.harold.fulcrum.api.data.integration;

import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.backend.json.JsonFileBackend;
import sh.harold.fulcrum.api.data.backend.mongo.MongoDataBackend;
import sh.harold.fulcrum.api.data.backend.sql.SqlDataBackend;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.query.*;
import sh.harold.fulcrum.api.data.query.backend.*;
import sh.harold.fulcrum.api.data.query.batch.BatchConfiguration;
import sh.harold.fulcrum.api.data.registry.PlayerDataRegistry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Factory class for creating CrossSchemaQueryBuilder instances.
 * This factory provides a centralized way to create query builders with appropriate
 * backend configurations and optimizations.
 */
public class QueryBuilderFactory {
    private static final Logger LOGGER = Logger.getLogger(QueryBuilderFactory.class.getName());
    private static final Map<Class<? extends PlayerDataBackend>, BatchConfiguration> BACKEND_CONFIGS = new ConcurrentHashMap<>();
    
    /**
     * Backend types for executor creation.
     */
    private enum BackendType {
        SQL, MONGODB, JSON, MIXED
    }
    
    private final PlayerDataBackend primaryBackend;
    private BatchConfiguration defaultConfiguration;
    private final Map<BackendType, SchemaJoinExecutor> executorCache = new ConcurrentHashMap<>();
    private final ExecutorService executorService;
    
    /**
     * Creates a new QueryBuilderFactory with the specified primary backend.
     *
     * @param primaryBackend The primary backend to use for query operations
     */
    public QueryBuilderFactory(PlayerDataBackend primaryBackend) {
        this(primaryBackend, ForkJoinPool.commonPool());
    }
    
    /**
     * Creates a new QueryBuilderFactory with custom executor service.
     *
     * @param primaryBackend The primary backend to use for query operations
     * @param executorService The executor service for async operations
     */
    public QueryBuilderFactory(PlayerDataBackend primaryBackend, ExecutorService executorService) {
        this.primaryBackend = primaryBackend;
        this.executorService = executorService;
        this.defaultConfiguration = createDefaultConfiguration();
        initializeBackendConfigurations();
    }
    
    /**
     * Creates a new CrossSchemaQueryBuilder instance.
     *
     * @return A new query builder instance
     */
    public CrossSchemaQueryBuilder createQueryBuilder() {
        // Create a dummy schema to start with - users will need to specify their root schema
        throw new UnsupportedOperationException("Please use createQueryBuilder(PlayerDataSchema) with a specific schema");
    }
    
    /**
     * Creates a new CrossSchemaQueryBuilder instance with a specific root schema.
     *
     * @param rootSchema The root schema to start the query from
     * @param <T> The type of the schema
     * @return A new query builder instance configured with the root schema
     */
    public <T> CrossSchemaQueryBuilder createQueryBuilder(PlayerDataSchema<T> rootSchema) {
        CrossSchemaQueryBuilder builder = CrossSchemaQueryBuilder.from(rootSchema);
        configureForBackend(builder);
        return builder;
    }
    
    /**
     * Creates a new CrossSchemaQueryBuilder for a specific backend type.
     *
     * @param backend The backend to create the query builder for
     * @return A new query builder instance configured for the specific backend
     */
    public CrossSchemaQueryBuilder createQueryBuilderForBackend(PlayerDataBackend backend, PlayerDataSchema<?> rootSchema) {
        CrossSchemaQueryBuilder builder = CrossSchemaQueryBuilder.from(rootSchema);
        configureForSpecificBackend(builder, backend);
        return builder;
    }
    
    /**
     * Creates a query builder from an existing PlayerDataRegistry.
     *
     * @return A new query builder instance that can query across all registered schemas
     */
    public static CrossSchemaQueryBuilder fromRegistry() {
        // Get the first backend from registry to use as primary
        PlayerDataBackend primaryBackend = null;
        for (PlayerDataSchema<?> schema : PlayerDataRegistry.allSchemas()) {
            primaryBackend = PlayerDataRegistry.getBackend(schema);
            if (primaryBackend != null) {
                break;
            }
        }
        
        if (primaryBackend == null) {
            throw new IllegalStateException("No backends registered in PlayerDataRegistry");
        }
        
        QueryBuilderFactory factory = new QueryBuilderFactory(primaryBackend);
        return factory.createQueryBuilder();
    }
    
    /**
     * Sets the default batch configuration for query operations.
     *
     * @param configuration The batch configuration to use as default
     */
    public void setDefaultConfiguration(BatchConfiguration configuration) {
        this.defaultConfiguration = configuration;
    }
    
    /**
     * Registers a custom batch configuration for a specific backend type.
     *
     * @param backendClass The backend class to register configuration for
     * @param configuration The batch configuration to use for this backend type
     */
    public static void registerBackendConfiguration(
            Class<? extends PlayerDataBackend> backendClass, 
            BatchConfiguration configuration) {
        BACKEND_CONFIGS.put(backendClass, configuration);
    }
    
    private void configureForBackend(CrossSchemaQueryBuilder builder) {
        configureForSpecificBackend(builder, primaryBackend);
    }
    
    private void configureForSpecificBackend(CrossSchemaQueryBuilder builder, PlayerDataBackend backend) {
        // Get configuration for specific backend type or use default
        BatchConfiguration config = BACKEND_CONFIGS.getOrDefault(backend.getClass(), defaultConfiguration);
        
        // Apply configuration to builder
        builder.withBatchConfig(config);
        
        // Log configuration
        LOGGER.fine(String.format("Configured query builder for %s backend with batch size %d",
                backend.getClass().getSimpleName(), config.getBatchSize()));
    }
    
    private BatchConfiguration createDefaultConfiguration() {
        return BatchConfiguration.builder()
                .batchSize(1000)
                .parallelism(4)
                .memoryPoolingEnabled(true)
                .build();
    }
    
    private void initializeBackendConfigurations() {
        // SQL backend optimized for batch queries
        BACKEND_CONFIGS.put(SqlDataBackend.class, BatchConfiguration.builder()
                .batchSize(5000)
                .parallelism(2)
                .memoryPoolingEnabled(true)
                .build());
        
        // MongoDB backend optimized for document operations
        BACKEND_CONFIGS.put(MongoDataBackend.class, BatchConfiguration.builder()
                .batchSize(1000)
                .parallelism(4)
                .memoryPoolingEnabled(true)
                .build());
        
        // JSON file backend with lower batch size due to file I/O
        BACKEND_CONFIGS.put(JsonFileBackend.class, BatchConfiguration.builder()
                .batchSize(100)
                .parallelism(8)
                .memoryPoolingEnabled(true)
                .build());
    }
    
    /**
     * Creates a factory builder for more complex configurations.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Builder for creating QueryBuilderFactory instances with custom configurations.
     */
    public static class Builder {
        private PlayerDataBackend primaryBackend;
        private BatchConfiguration defaultConfiguration;
        private final Map<Class<? extends PlayerDataBackend>, BatchConfiguration> customConfigs = new ConcurrentHashMap<>();
        
        public Builder primaryBackend(PlayerDataBackend backend) {
            this.primaryBackend = backend;
            return this;
        }
        
        public Builder defaultConfiguration(BatchConfiguration configuration) {
            this.defaultConfiguration = configuration;
            return this;
        }
        
        public Builder withBackendConfiguration(
                Class<? extends PlayerDataBackend> backendClass,
                BatchConfiguration configuration) {
            customConfigs.put(backendClass, configuration);
            return this;
        }
        
        public QueryBuilderFactory build() {
            if (primaryBackend == null) {
                throw new IllegalStateException("Primary backend must be specified");
            }
            
            QueryBuilderFactory factory = new QueryBuilderFactory(primaryBackend);
            
            if (defaultConfiguration != null) {
                factory.setDefaultConfiguration(defaultConfiguration);
            }
            
            customConfigs.forEach(QueryBuilderFactory::registerBackendConfiguration);
            
            return factory;
        }
    }
    
    /**
     * Creates an appropriate executor for the given query.
     * Merges functionality from BackendSpecificExecutorFactory.
     * 
     * @param queryBuilder The query builder
     * @return A schema join executor optimized for the query's backends
     */
    public SchemaJoinExecutor createExecutor(CrossSchemaQueryBuilder queryBuilder) {
        BackendType backendType = analyzeBackends(queryBuilder);
        
        LOGGER.log(Level.FINE, "Creating executor for backend type: {0}", backendType);
        
        return executorCache.computeIfAbsent(backendType, this::createExecutorForType);
    }
    
    
    /**
     * Analyzes the backends used in a query to determine the best executor type.
     */
    private BackendType analyzeBackends(CrossSchemaQueryBuilder queryBuilder) {
        Set<PlayerDataSchema<?>> schemas = new HashSet<>();
        schemas.add(queryBuilder.getRootSchema());
        
        for (JoinOperation join : queryBuilder.getJoins()) {
            schemas.add(join.getTargetSchema());
        }
        
        if (schemas.isEmpty()) {
            return BackendType.MIXED;
        }
        
        // Collect backend types
        Set<Class<? extends PlayerDataBackend>> backendTypes = new HashSet<>();
        
        for (PlayerDataSchema<?> schema : schemas) {
            PlayerDataBackend backend = PlayerDataRegistry.getBackend(schema);
            if (backend == null) {
                LOGGER.log(Level.WARNING, "No backend registered for schema: {0}", schema.schemaKey());
                return BackendType.MIXED;
            }
            backendTypes.add(backend.getClass());
        }
        
        // If multiple backend types, use mixed
        if (backendTypes.size() > 1) {
            LOGGER.log(Level.FINE, "Multiple backend types detected, using MIXED executor");
            return BackendType.MIXED;
        }
        
        // Single backend type - check for specific optimizations
        Class<? extends PlayerDataBackend> backendClass = backendTypes.iterator().next();
        
        if (SqlDataBackend.class.isAssignableFrom(backendClass)) {
            return BackendType.SQL;
        } else if (MongoDataBackend.class.isAssignableFrom(backendClass)) {
            return BackendType.MONGODB;
        } else if (JsonFileBackend.class.isAssignableFrom(backendClass)) {
            return BackendType.JSON;
        }
        
        return BackendType.MIXED;
    }
    
    /**
     * Creates an executor for the specified backend type.
     */
    private SchemaJoinExecutor createExecutorForType(BackendType backendType) {
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
}