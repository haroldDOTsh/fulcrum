package sh.harold.fulcrum.api.data.query.batch;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Configuration for batch operations including performance tuning parameters.
 * Provides sensible defaults for most use cases while allowing fine-tuning
 * for specific scenarios.
 * 
 * <p>This configuration controls:</p>
 * <ul>
 *   <li>Batch sizes for processing</li>
 *   <li>Thread pool configuration</li>
 *   <li>Connection pooling settings</li>
 *   <li>Memory management options</li>
 *   <li>Retry and timeout policies</li>
 * </ul>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * BatchConfiguration config = BatchConfiguration.builder()
 *     .batchSize(5000)
 *     .parallelism(8)
 *     .maxConnections(20)
 *     .timeoutMs(30000)
 *     .memoryPoolingEnabled(true)
 *     .build();
 * }</pre>
 * 
 * @author Harold
 * @since 1.0
 */
public class BatchConfiguration {
    
    // Default values optimized for typical Minecraft server environments
    private static final int DEFAULT_BATCH_SIZE = 1000;
    private static final int DEFAULT_PARALLELISM = Runtime.getRuntime().availableProcessors();
    private static final int DEFAULT_MAX_CONNECTIONS = 10;
    private static final int DEFAULT_PREPARED_STATEMENT_CACHE_SIZE = 100;
    private static final long DEFAULT_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5);
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_RETRY_DELAY_MS = 1000;
    private static final int DEFAULT_CORE_POOL_SIZE = 4;
    private static final int DEFAULT_MAX_POOL_SIZE = 16;
    private static final int DEFAULT_QUEUE_CAPACITY = 1000;
    
    // Configuration fields
    private int batchSize;
    private int parallelism;
    private int maxConnections;
    private int preparedStatementCacheSize;
    private boolean memoryPoolingEnabled;
    private boolean bulkOperationsEnabled;
    private long timeoutMs;
    private int maxRetries;
    private long retryDelayMs;
    private int corePoolSize;
    private int maxPoolSize;
    private int queueCapacity;
    
    // Performance tuning fields
    private int connectionTimeout;
    private int socketTimeout;
    private boolean useBatchInserts;
    private boolean useServerPrepStmts;
    private int maxBatchStatements;
    private boolean rewriteBatchedStatements;
    
    /**
     * Creates a configuration with default values.
     */
    public BatchConfiguration() {
        this.batchSize = DEFAULT_BATCH_SIZE;
        this.parallelism = DEFAULT_PARALLELISM;
        this.maxConnections = DEFAULT_MAX_CONNECTIONS;
        this.preparedStatementCacheSize = DEFAULT_PREPARED_STATEMENT_CACHE_SIZE;
        this.memoryPoolingEnabled = true;
        this.bulkOperationsEnabled = true;
        this.timeoutMs = DEFAULT_TIMEOUT_MS;
        this.maxRetries = DEFAULT_MAX_RETRIES;
        this.retryDelayMs = DEFAULT_RETRY_DELAY_MS;
        this.corePoolSize = DEFAULT_CORE_POOL_SIZE;
        this.maxPoolSize = DEFAULT_MAX_POOL_SIZE;
        this.queueCapacity = DEFAULT_QUEUE_CAPACITY;
        
        // Performance defaults
        this.connectionTimeout = 5000;
        this.socketTimeout = 30000;
        this.useBatchInserts = true;
        this.useServerPrepStmts = true;
        this.maxBatchStatements = 100;
        this.rewriteBatchedStatements = true;
    }
    
    /**
     * Creates a default configuration instance.
     * 
     * @return A new configuration with default values
     */
    public static BatchConfiguration defaultConfig() {
        return new BatchConfiguration();
    }
    
    /**
     * Creates a configuration builder.
     * 
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Creates a configuration optimized for small datasets (&lt; 10K records).
     *
     * @return A configuration tuned for small datasets
     */
    public static BatchConfiguration smallDataset() {
        return builder()
            .batchSize(100)
            .parallelism(2)
            .maxConnections(4)
            .memoryPoolingEnabled(false)
            .build();
    }
    
    /**
     * Creates a configuration optimized for large datasets (&gt; 1M records).
     *
     * @return A configuration tuned for large datasets
     */
    public static BatchConfiguration largeDataset() {
        return builder()
            .batchSize(10000)
            .parallelism(DEFAULT_PARALLELISM * 2)
            .maxConnections(50)
            .memoryPoolingEnabled(true)
            .bulkOperationsEnabled(true)
            .preparedStatementCacheSize(500)
            .maxPoolSize(32)
            .queueCapacity(5000)
            .build();
    }
    
    // Getters and setters
    
    public int getBatchSize() {
        return batchSize;
    }
    
    public void setBatchSize(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Batch size must be positive");
        }
        this.batchSize = batchSize;
    }
    
    public int getParallelism() {
        return parallelism;
    }
    
    public void setParallelism(int parallelism) {
        if (parallelism <= 0) {
            throw new IllegalArgumentException("Parallelism must be positive");
        }
        this.parallelism = parallelism;
    }
    
    public int getMaxConnections() {
        return maxConnections;
    }
    
    public void setMaxConnections(int maxConnections) {
        if (maxConnections <= 0) {
            throw new IllegalArgumentException("Max connections must be positive");
        }
        this.maxConnections = maxConnections;
    }
    
    public int getPreparedStatementCacheSize() {
        return preparedStatementCacheSize;
    }
    
    public void setPreparedStatementCacheSize(int preparedStatementCacheSize) {
        if (preparedStatementCacheSize < 0) {
            throw new IllegalArgumentException("Cache size cannot be negative");
        }
        this.preparedStatementCacheSize = preparedStatementCacheSize;
    }
    
    public boolean isMemoryPoolingEnabled() {
        return memoryPoolingEnabled;
    }
    
    public void setMemoryPoolingEnabled(boolean memoryPoolingEnabled) {
        this.memoryPoolingEnabled = memoryPoolingEnabled;
    }
    
    public boolean isBulkOperationsEnabled() {
        return bulkOperationsEnabled;
    }
    
    public void setBulkOperationsEnabled(boolean bulkOperationsEnabled) {
        this.bulkOperationsEnabled = bulkOperationsEnabled;
    }
    
    public long getTimeoutMs() {
        return timeoutMs;
    }
    
    public void setTimeoutMs(long timeoutMs) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        this.timeoutMs = timeoutMs;
    }
    
    public int getMaxRetries() {
        return maxRetries;
    }
    
    public void setMaxRetries(int maxRetries) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("Max retries cannot be negative");
        }
        this.maxRetries = maxRetries;
    }
    
    public long getRetryDelayMs() {
        return retryDelayMs;
    }
    
    public void setRetryDelayMs(long retryDelayMs) {
        if (retryDelayMs < 0) {
            throw new IllegalArgumentException("Retry delay cannot be negative");
        }
        this.retryDelayMs = retryDelayMs;
    }
    
    public int getCorePoolSize() {
        return corePoolSize;
    }
    
    public void setCorePoolSize(int corePoolSize) {
        if (corePoolSize <= 0) {
            throw new IllegalArgumentException("Core pool size must be positive");
        }
        this.corePoolSize = corePoolSize;
    }
    
    public int getMaxPoolSize() {
        return maxPoolSize;
    }
    
    public void setMaxPoolSize(int maxPoolSize) {
        if (maxPoolSize <= 0) {
            throw new IllegalArgumentException("Max pool size must be positive");
        }
        this.maxPoolSize = maxPoolSize;
    }
    
    public int getQueueCapacity() {
        return queueCapacity;
    }
    
    public void setQueueCapacity(int queueCapacity) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("Queue capacity must be positive");
        }
        this.queueCapacity = queueCapacity;
    }
    
    public int getConnectionTimeout() {
        return connectionTimeout;
    }
    
    public void setConnectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }
    
    public int getSocketTimeout() {
        return socketTimeout;
    }
    
    public void setSocketTimeout(int socketTimeout) {
        this.socketTimeout = socketTimeout;
    }
    
    public boolean isUseBatchInserts() {
        return useBatchInserts;
    }
    
    public void setUseBatchInserts(boolean useBatchInserts) {
        this.useBatchInserts = useBatchInserts;
    }
    
    public boolean isUseServerPrepStmts() {
        return useServerPrepStmts;
    }
    
    public void setUseServerPrepStmts(boolean useServerPrepStmts) {
        this.useServerPrepStmts = useServerPrepStmts;
    }
    
    public int getMaxBatchStatements() {
        return maxBatchStatements;
    }
    
    public void setMaxBatchStatements(int maxBatchStatements) {
        this.maxBatchStatements = maxBatchStatements;
    }
    
    public boolean isRewriteBatchedStatements() {
        return rewriteBatchedStatements;
    }
    
    public void setRewriteBatchedStatements(boolean rewriteBatchedStatements) {
        this.rewriteBatchedStatements = rewriteBatchedStatements;
    }
    
    /**
     * Builder for creating BatchConfiguration instances.
     */
    public static class Builder {
        private final BatchConfiguration config;
        
        private Builder() {
            this.config = new BatchConfiguration();
        }
        
        public Builder batchSize(int batchSize) {
            config.setBatchSize(batchSize);
            return this;
        }
        
        public Builder parallelism(int parallelism) {
            config.setParallelism(parallelism);
            return this;
        }
        
        public Builder maxConnections(int maxConnections) {
            config.setMaxConnections(maxConnections);
            return this;
        }
        
        public Builder preparedStatementCacheSize(int size) {
            config.setPreparedStatementCacheSize(size);
            return this;
        }
        
        public Builder memoryPoolingEnabled(boolean enabled) {
            config.setMemoryPoolingEnabled(enabled);
            return this;
        }
        
        public Builder bulkOperationsEnabled(boolean enabled) {
            config.setBulkOperationsEnabled(enabled);
            return this;
        }
        
        public Builder timeoutMs(long timeoutMs) {
            config.setTimeoutMs(timeoutMs);
            return this;
        }
        
        public Builder timeout(long duration, TimeUnit unit) {
            config.setTimeoutMs(unit.toMillis(duration));
            return this;
        }
        
        public Builder maxRetries(int maxRetries) {
            config.setMaxRetries(maxRetries);
            return this;
        }
        
        public Builder retryDelayMs(long retryDelayMs) {
            config.setRetryDelayMs(retryDelayMs);
            return this;
        }
        
        public Builder retryDelay(long duration, TimeUnit unit) {
            config.setRetryDelayMs(unit.toMillis(duration));
            return this;
        }
        
        public Builder corePoolSize(int corePoolSize) {
            config.setCorePoolSize(corePoolSize);
            return this;
        }
        
        public Builder maxPoolSize(int maxPoolSize) {
            config.setMaxPoolSize(maxPoolSize);
            return this;
        }
        
        public Builder queueCapacity(int queueCapacity) {
            config.setQueueCapacity(queueCapacity);
            return this;
        }
        
        public Builder connectionTimeout(int connectionTimeout) {
            config.setConnectionTimeout(connectionTimeout);
            return this;
        }
        
        public Builder socketTimeout(int socketTimeout) {
            config.setSocketTimeout(socketTimeout);
            return this;
        }
        
        public Builder useBatchInserts(boolean useBatchInserts) {
            config.setUseBatchInserts(useBatchInserts);
            return this;
        }
        
        public Builder useServerPrepStmts(boolean useServerPrepStmts) {
            config.setUseServerPrepStmts(useServerPrepStmts);
            return this;
        }
        
        public Builder maxBatchStatements(int maxBatchStatements) {
            config.setMaxBatchStatements(maxBatchStatements);
            return this;
        }
        
        public Builder rewriteBatchedStatements(boolean rewriteBatchedStatements) {
            config.setRewriteBatchedStatements(rewriteBatchedStatements);
            return this;
        }
        
        public BatchConfiguration build() {
            validateConfiguration();
            return config;
        }
        
        private void validateConfiguration() {
            if (config.getCorePoolSize() > config.getMaxPoolSize()) {
                throw new IllegalStateException("Core pool size cannot be greater than max pool size");
            }
            if (config.getRetryDelayMs() > config.getTimeoutMs()) {
                throw new IllegalStateException("Retry delay cannot be greater than timeout");
            }
            if (config.getBatchSize() > config.getQueueCapacity()) {
                throw new IllegalStateException("Batch size should not exceed queue capacity");
            }
        }
    }
    
    @Override
    public String toString() {
        return String.format("BatchConfiguration[batchSize=%d, parallelism=%d, maxConnections=%d, " +
                "memoryPooling=%s, bulkOps=%s, timeout=%dms]",
                batchSize, parallelism, maxConnections, memoryPoolingEnabled, 
                bulkOperationsEnabled, timeoutMs);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BatchConfiguration that = (BatchConfiguration) o;
        return batchSize == that.batchSize &&
                parallelism == that.parallelism &&
                maxConnections == that.maxConnections &&
                preparedStatementCacheSize == that.preparedStatementCacheSize &&
                memoryPoolingEnabled == that.memoryPoolingEnabled &&
                bulkOperationsEnabled == that.bulkOperationsEnabled &&
                timeoutMs == that.timeoutMs &&
                maxRetries == that.maxRetries &&
                retryDelayMs == that.retryDelayMs;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(batchSize, parallelism, maxConnections, preparedStatementCacheSize,
                memoryPoolingEnabled, bulkOperationsEnabled, timeoutMs, maxRetries, retryDelayMs);
    }
}