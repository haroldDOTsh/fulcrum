package sh.harold.fulcrum.api.data.integration;

import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.backend.json.JsonFileBackend;
import sh.harold.fulcrum.api.data.backend.mongo.MongoDataBackend;
import sh.harold.fulcrum.api.data.backend.sql.SqlDataBackend;
import sh.harold.fulcrum.api.data.query.batch.BatchConfiguration;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Configuration management for the query builder system.
 * This class handles loading and managing configuration settings for cross-schema
 * queries, including backend-specific optimizations, caching settings, and performance tuning.
 * 
 * <p>Configuration can be loaded from:</p>
 * <ul>
 *   <li>Properties files</li>
 *   <li>Environment variables</li>
 *   <li>Programmatic configuration</li>
 *   <li>Default settings</li>
 * </ul>
 * 
 * <p>Example configuration file (query-builder.properties):</p>
 * <pre>
 * # General Settings
 * query.cache.enabled=true
 * query.cache.ttl=300000
 * query.cache.maxSize=1000
 * 
 * # Batch Configuration
 * query.batch.size=1000
 * query.batch.parallelism=4
 * query.batch.timeout=300000
 * 
 * # SQL Backend Settings
 * query.sql.batchSize=5000
 * query.sql.parallelism=2
 * query.sql.preparedStatementCache=200
 * 
 * # MongoDB Backend Settings
 * query.mongodb.batchSize=1000
 * query.mongodb.parallelism=4
 * 
 * # JSON Backend Settings
 * query.json.batchSize=100
 * query.json.parallelism=8
 * </pre>
 * 
 * @author Harold
 * @since 1.0
 */
public class QueryBuilderConfiguration {
    
    private static final Logger LOGGER = Logger.getLogger(QueryBuilderConfiguration.class.getName());
    
    // Configuration keys
    private static final String PREFIX = "query.";
    private static final String CACHE_PREFIX = PREFIX + "cache.";
    private static final String BATCH_PREFIX = PREFIX + "batch.";
    
    // Default configuration file names
    private static final String[] CONFIG_FILE_NAMES = {
        "query-builder.properties",
        "query-config.properties",
        "fulcrum-query.properties"
    };
    
    // Configuration properties
    private final Properties properties;
    
    // Cached configurations
    private BatchConfiguration defaultBatchConfig;
    private final Map<Class<? extends PlayerDataBackend>, BatchConfiguration> backendConfigs;
    
    // Cache settings
    private boolean cacheEnabled = true;
    private long cacheTTL = 300000; // 5 minutes
    private int cacheMaxSize = 1000;
    
    // Query settings
    private int defaultBatchSize = 1000;
    private int defaultParallelism = Runtime.getRuntime().availableProcessors();
    private long defaultTimeout = 300000; // 5 minutes
    private boolean memoryOptimizationEnabled = true;
    private boolean bulkOperationsEnabled = true;
    
    /**
     * Creates a new configuration with default settings.
     */
    public QueryBuilderConfiguration() {
        this.properties = new Properties();
        this.backendConfigs = new HashMap<>();
        loadDefaults();
    }
    
    /**
     * Creates a new configuration from a properties object.
     *
     * @param properties The properties to load from
     */
    public QueryBuilderConfiguration(Properties properties) {
        this.properties = new Properties(properties);
        this.backendConfigs = new HashMap<>();
        loadFromProperties();
    }
    
    /**
     * Loads configuration from a file.
     *
     * @param configFile The configuration file
     * @return A new configuration instance
     * @throws IOException If the file cannot be read
     */
    public static QueryBuilderConfiguration fromFile(File configFile) throws IOException {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(configFile)) {
            props.load(fis);
        }
        LOGGER.info("Loaded query builder configuration from: " + configFile.getAbsolutePath());
        return new QueryBuilderConfiguration(props);
    }
    
    /**
     * Loads configuration from the classpath.
     *
     * @param resourcePath The resource path
     * @return A new configuration instance
     * @throws IOException If the resource cannot be read
     */
    public static QueryBuilderConfiguration fromClasspath(String resourcePath) throws IOException {
        Properties props = new Properties();
        try (var is = QueryBuilderConfiguration.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            props.load(is);
        }
        LOGGER.info("Loaded query builder configuration from classpath: " + resourcePath);
        return new QueryBuilderConfiguration(props);
    }
    
    /**
     * Attempts to auto-discover and load configuration.
     *
     * @return A configuration instance (with defaults if no config found)
     */
    public static QueryBuilderConfiguration autoDiscover() {
        // Try environment variable first
        String configPath = System.getenv("QUERY_BUILDER_CONFIG");
        if (configPath != null) {
            try {
                return fromFile(new File(configPath));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to load config from env variable: " + configPath, e);
            }
        }
        
        // Try common file names in working directory
        for (String fileName : CONFIG_FILE_NAMES) {
            File configFile = new File(fileName);
            if (configFile.exists()) {
                try {
                    return fromFile(configFile);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to load config file: " + fileName, e);
                }
            }
        }
        
        // Try classpath
        for (String fileName : CONFIG_FILE_NAMES) {
            try {
                return fromClasspath(fileName);
            } catch (IOException e) {
                // Continue trying other locations
            }
        }
        
        LOGGER.info("No configuration file found, using defaults");
        return new QueryBuilderConfiguration();
    }
    
    /**
     * Gets the default batch configuration.
     *
     * @return The default batch configuration
     */
    public BatchConfiguration getDefaultBatchConfiguration() {
        if (defaultBatchConfig == null) {
            defaultBatchConfig = BatchConfiguration.builder()
                .batchSize(defaultBatchSize)
                .parallelism(defaultParallelism)
                .timeoutMs(defaultTimeout)
                .memoryPoolingEnabled(memoryOptimizationEnabled)
                .bulkOperationsEnabled(bulkOperationsEnabled)
                .build();
        }
        return defaultBatchConfig;
    }
    
    /**
     * Gets backend-specific batch configuration.
     *
     * @param backendClass The backend class
     * @return The configuration for the backend, or default if not configured
     */
    public BatchConfiguration getBatchConfiguration(Class<? extends PlayerDataBackend> backendClass) {
        return backendConfigs.getOrDefault(backendClass, getDefaultBatchConfiguration());
    }
    
    /**
     * Sets backend-specific batch configuration.
     *
     * @param backendClass The backend class
     * @param config The configuration
     */
    public void setBatchConfiguration(Class<? extends PlayerDataBackend> backendClass, BatchConfiguration config) {
        backendConfigs.put(backendClass, config);
    }
    
    /**
     * Applies this configuration to a QueryBuilderFactory.
     *
     * @param factory The factory to configure
     */
    public void applyTo(QueryBuilderFactory factory) {
        factory.setDefaultConfiguration(getDefaultBatchConfiguration());
        
        backendConfigs.forEach((backendClass, config) -> {
            QueryBuilderFactory.registerBackendConfiguration(backendClass, config);
        });
    }
    
    /**
     * Applies this configuration to a PlayerDataQueryManager.
     *
     * @param manager The manager to configure
     */
    public void applyTo(PlayerDataQueryManager manager) {
        manager.setCachingEnabled(cacheEnabled);
        manager.setDefaultCacheTTL(cacheTTL);
        manager.setMaxCacheSize(cacheMaxSize);
    }
    
    // Getters and setters
    
    public boolean isCacheEnabled() {
        return cacheEnabled;
    }
    
    public void setCacheEnabled(boolean cacheEnabled) {
        this.cacheEnabled = cacheEnabled;
    }
    
    public long getCacheTTL() {
        return cacheTTL;
    }
    
    public void setCacheTTL(long cacheTTL) {
        this.cacheTTL = cacheTTL;
    }
    
    public int getCacheMaxSize() {
        return cacheMaxSize;
    }
    
    public void setCacheMaxSize(int cacheMaxSize) {
        this.cacheMaxSize = cacheMaxSize;
    }
    
    public int getDefaultBatchSize() {
        return defaultBatchSize;
    }
    
    public void setDefaultBatchSize(int defaultBatchSize) {
        this.defaultBatchSize = defaultBatchSize;
        this.defaultBatchConfig = null; // Reset cache
    }
    
    public int getDefaultParallelism() {
        return defaultParallelism;
    }
    
    public void setDefaultParallelism(int defaultParallelism) {
        this.defaultParallelism = defaultParallelism;
        this.defaultBatchConfig = null; // Reset cache
    }
    
    public long getDefaultTimeout() {
        return defaultTimeout;
    }
    
    public void setDefaultTimeout(long defaultTimeout) {
        this.defaultTimeout = defaultTimeout;
        this.defaultBatchConfig = null; // Reset cache
    }
    
    // Private helper methods
    
    private void loadDefaults() {
        // Load SQL backend defaults
        backendConfigs.put(SqlDataBackend.class, BatchConfiguration.builder()
            .batchSize(5000)
            .parallelism(2)
            .memoryPoolingEnabled(true)
            .preparedStatementCacheSize(200)
            .build());
        
        // Load MongoDB backend defaults
        backendConfigs.put(MongoDataBackend.class, BatchConfiguration.builder()
            .batchSize(1000)
            .parallelism(4)
            .memoryPoolingEnabled(true)
            .build());
        
        // Load JSON backend defaults
        backendConfigs.put(JsonFileBackend.class, BatchConfiguration.builder()
            .batchSize(100)
            .parallelism(8)
            .memoryPoolingEnabled(true)
            .build());
    }
    
    private void loadFromProperties() {
        // Load cache settings
        cacheEnabled = getBooleanProperty(CACHE_PREFIX + "enabled", cacheEnabled);
        cacheTTL = getLongProperty(CACHE_PREFIX + "ttl", cacheTTL);
        cacheMaxSize = getIntProperty(CACHE_PREFIX + "maxSize", cacheMaxSize);
        
        // Load default batch settings
        defaultBatchSize = getIntProperty(BATCH_PREFIX + "size", defaultBatchSize);
        defaultParallelism = getIntProperty(BATCH_PREFIX + "parallelism", defaultParallelism);
        defaultTimeout = getLongProperty(BATCH_PREFIX + "timeout", defaultTimeout);
        memoryOptimizationEnabled = getBooleanProperty(BATCH_PREFIX + "memoryOptimization", memoryOptimizationEnabled);
        bulkOperationsEnabled = getBooleanProperty(BATCH_PREFIX + "bulkOperations", bulkOperationsEnabled);
        
        // Load backend-specific settings
        loadBackendConfig("sql", SqlDataBackend.class);
        loadBackendConfig("mongodb", MongoDataBackend.class);
        loadBackendConfig("json", JsonFileBackend.class);
    }
    
    private void loadBackendConfig(String backendName, Class<? extends PlayerDataBackend> backendClass) {
        String prefix = PREFIX + backendName + ".";
        
        // Check if any properties exist for this backend
        boolean hasConfig = properties.stringPropertyNames().stream()
            .anyMatch(key -> key.startsWith(prefix));
        
        if (hasConfig) {
            BatchConfiguration.Builder builder = BatchConfiguration.builder();
            
            // Load all possible settings
            int batchSize = getIntProperty(prefix + "batchSize", -1);
            if (batchSize > 0) builder.batchSize(batchSize);
            
            int parallelism = getIntProperty(prefix + "parallelism", -1);
            if (parallelism > 0) builder.parallelism(parallelism);
            
            long timeout = getLongProperty(prefix + "timeout", -1);
            if (timeout > 0) builder.timeoutMs(timeout);
            
            builder.memoryPoolingEnabled(getBooleanProperty(prefix + "memoryPooling", true));
            builder.bulkOperationsEnabled(getBooleanProperty(prefix + "bulkOperations", true));
            
            int prepStmtCache = getIntProperty(prefix + "preparedStatementCache", -1);
            if (prepStmtCache > 0) builder.preparedStatementCacheSize(prepStmtCache);
            
            backendConfigs.put(backendClass, builder.build());
        }
    }
    
    private String getProperty(String key, String defaultValue) {
        // Check environment variable first (convert dots to underscores)
        String envKey = key.toUpperCase().replace('.', '_');
        String envValue = System.getenv(envKey);
        if (envValue != null) {
            return envValue;
        }
        
        // Check properties
        return properties.getProperty(key, defaultValue);
    }
    
    private int getIntProperty(String key, int defaultValue) {
        String value = getProperty(key, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            LOGGER.warning("Invalid integer value for " + key + ": " + value);
            return defaultValue;
        }
    }
    
    private long getLongProperty(String key, long defaultValue) {
        String value = getProperty(key, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            LOGGER.warning("Invalid long value for " + key + ": " + value);
            return defaultValue;
        }
    }
    
    private boolean getBooleanProperty(String key, boolean defaultValue) {
        String value = getProperty(key, null);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }
    
    /**
     * Builder for creating QueryBuilderConfiguration instances.
     */
    public static class Builder {
        private final QueryBuilderConfiguration config;
        
        public Builder() {
            this.config = new QueryBuilderConfiguration();
        }
        
        public Builder cacheEnabled(boolean enabled) {
            config.setCacheEnabled(enabled);
            return this;
        }
        
        public Builder cacheTTL(long ttl, TimeUnit unit) {
            config.setCacheTTL(unit.toMillis(ttl));
            return this;
        }
        
        public Builder cacheMaxSize(int maxSize) {
            config.setCacheMaxSize(maxSize);
            return this;
        }
        
        public Builder defaultBatchSize(int size) {
            config.setDefaultBatchSize(size);
            return this;
        }
        
        public Builder defaultParallelism(int parallelism) {
            config.setDefaultParallelism(parallelism);
            return this;
        }
        
        public Builder defaultTimeout(long timeout, TimeUnit unit) {
            config.setDefaultTimeout(unit.toMillis(timeout));
            return this;
        }
        
        public Builder backendConfiguration(Class<? extends PlayerDataBackend> backendClass, BatchConfiguration batchConfig) {
            config.setBatchConfiguration(backendClass, batchConfig);
            return this;
        }
        
        public QueryBuilderConfiguration build() {
            return config;
        }
    }
    
    /**
     * Creates a new builder.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
}