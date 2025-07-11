package sh.harold.fulcrum.api.data.integration;

import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.backend.json.JsonFileBackend;
import sh.harold.fulcrum.api.data.backend.mongo.MongoDataBackend;
import sh.harold.fulcrum.api.data.backend.sql.SqlDataBackend;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.query.CrossSchemaQueryBuilder;
import sh.harold.fulcrum.api.data.query.batch.BatchConfiguration;
import sh.harold.fulcrum.api.data.registry.PlayerDataRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Factory class for creating CrossSchemaQueryBuilder instances.
 * This factory provides a centralized way to create query builders with appropriate
 * backend configurations and optimizations.
 */
public class QueryBuilderFactory {
    private static final Logger LOGGER = Logger.getLogger(QueryBuilderFactory.class.getName());
    private static final Map<Class<? extends PlayerDataBackend>, BatchConfiguration> BACKEND_CONFIGS = new ConcurrentHashMap<>();
    
    private final PlayerDataBackend primaryBackend;
    private BatchConfiguration defaultConfiguration;
    
    /**
     * Creates a new QueryBuilderFactory with the specified primary backend.
     *
     * @param primaryBackend The primary backend to use for query operations
     */
    public QueryBuilderFactory(PlayerDataBackend primaryBackend) {
        this.primaryBackend = primaryBackend;
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
}