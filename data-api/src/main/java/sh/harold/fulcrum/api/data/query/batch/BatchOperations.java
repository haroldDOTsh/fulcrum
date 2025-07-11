package sh.harold.fulcrum.api.data.query.batch;

import sh.harold.fulcrum.api.data.query.CrossSchemaResult;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Main interface for batch operations on cross-schema queries.
 * Provides methods for efficient batch retrieval and modification of large datasets.
 * 
 * <p>All operations are designed to handle 1M+ entries efficiently in a
 * Minecraft server environment with full async support to prevent server lag.</p>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * BatchOperations batch = queryBuilder.batch();
 * 
 * // Batch load multiple UUID sets
 * List<List<CrossSchemaResult>> results = batch
 *     .batchLoad(uuidBatches)
 *     .get();
 * 
 * // Batch update with transaction support
 * BatchResult updateResult = batch
 *     .withTransaction()
 *     .batchUpdate(uuids, Map.of("lastSeen", Instant.now()))
 *     .get();
 * }</pre>
 * 
 * @author Harold
 * @since 1.0
 */
public interface BatchOperations {
    
    // =====================
    // Batch Query Operations
    // =====================
    
    /**
     * Loads multiple batches of UUIDs across schemas.
     * Each batch is processed independently for maximum efficiency.
     * 
     * @param uuidBatches List of UUID sets to load
     * @return Future containing results for each batch
     */
    CompletableFuture<List<List<CrossSchemaResult>>> batchLoad(List<Set<UUID>> uuidBatches);
    
    /**
     * Loads a single batch of UUIDs across schemas.
     * 
     * @param uuids Set of UUIDs to load
     * @return Future containing results for the batch
     */
    CompletableFuture<List<CrossSchemaResult>> batchLoad(Set<UUID> uuids);
    
    /**
     * Streams batches of results instead of individual items.
     * More efficient for processing large datasets as it reduces overhead.
     * 
     * @return Future containing a stream of result batches
     */
    CompletableFuture<Stream<List<CrossSchemaResult>>> batchStream();
    
    /**
     * Streams batches with a specified batch size.
     * 
     * @param batchSize Size of each batch
     * @return Future containing a stream of result batches
     */
    CompletableFuture<Stream<List<CrossSchemaResult>>> batchStream(int batchSize);
    
    /**
     * Executes batch query with parallel processing.
     * 
     * @param parallelism Number of parallel batches to process
     * @return Future containing all results
     */
    CompletableFuture<List<CrossSchemaResult>> executeInBatches(int parallelism);
    
    // ===========================
    // Batch Modification Operations
    // ===========================
    
    /**
     * Updates multiple records across schemas in batches.
     * 
     * @param uuids UUIDs to update
     * @param updates Map of field names to values to update
     * @return Future containing batch operation result
     */
    CompletableFuture<BatchResult> batchUpdate(List<UUID> uuids, Map<String, Object> updates);
    
    /**
     * Updates multiple records with schema-specific updates.
     * 
     * @param updates Map of schema to update operations
     * @return Future containing batch operation result
     */
    CompletableFuture<BatchResult> batchUpdate(Map<PlayerDataSchema<?>, Map<UUID, Map<String, Object>>> updates);
    
    /**
     * Applies a transformation function to multiple records.
     * 
     * @param uuids UUIDs to transform
     * @param transformer Function to transform each result
     * @return Future containing batch operation result
     */
    CompletableFuture<BatchResult> batchApply(List<UUID> uuids, 
                                               Function<CrossSchemaResult, CrossSchemaResult> transformer);
    
    /**
     * Deletes multiple records across schemas.
     * 
     * @param uuids UUIDs to delete
     * @return Future containing batch operation result
     */
    CompletableFuture<BatchResult> batchDelete(List<UUID> uuids);
    
    /**
     * Deletes records with schema-specific targeting.
     * 
     * @param deletions Map of schema to UUIDs to delete
     * @return Future containing batch operation result
     */
    CompletableFuture<BatchResult> batchDelete(Map<PlayerDataSchema<?>, Set<UUID>> deletions);
    
    /**
     * Inserts or updates multiple records (upsert operation).
     * 
     * @param results Records to upsert
     * @return Future containing batch operation result
     */
    CompletableFuture<BatchResult> batchUpsert(List<CrossSchemaResult> results);
    
    /**
     * Inserts multiple new records.
     * 
     * @param results Records to insert
     * @return Future containing batch operation result
     */
    CompletableFuture<BatchResult> batchInsert(List<CrossSchemaResult> results);
    
    // =======================
    // Configuration Methods
    // =======================
    
    /**
     * Sets the batch size for operations.
     * 
     * @param size Batch size (must be positive)
     * @return This instance for method chaining
     */
    BatchOperations withBatchSize(int size);
    
    /**
     * Enables parallel batch processing with specified concurrency.
     * 
     * @param concurrency Number of concurrent batches to process
     * @return This instance for method chaining
     */
    BatchOperations withParallelism(int concurrency);
    
    /**
     * Enables connection pooling for database operations.
     * 
     * @param maxConnections Maximum number of pooled connections
     * @return This instance for method chaining
     */
    BatchOperations withConnectionPooling(int maxConnections);
    
    /**
     * Enables prepared statement caching.
     * 
     * @param maxCacheSize Maximum number of cached statements
     * @return This instance for method chaining
     */
    BatchOperations withPreparedStatementCache(int maxCacheSize);
    
    /**
     * Enables memory pooling for object reuse.
     * 
     * @param enabled Whether to enable memory pooling
     * @return This instance for method chaining
     */
    BatchOperations withMemoryPooling(boolean enabled);
    
    /**
     * Enables database-specific bulk operations.
     * 
     * @param enabled Whether to enable bulk operations
     * @return This instance for method chaining
     */
    BatchOperations withBulkOperations(boolean enabled);
    
    /**
     * Enables transactional batch operations.
     * 
     * @return This instance for method chaining
     */
    BatchOperations withTransaction();
    
    /**
     * Sets transaction isolation level.
     * 
     * @param isolationLevel The isolation level
     * @return This instance for method chaining
     */
    BatchOperations withTransactionIsolation(TransactionIsolation isolationLevel);
    
    /**
     * Sets the timeout for batch operations.
     * 
     * @param timeoutMs Timeout in milliseconds
     * @return This instance for method chaining
     */
    BatchOperations withTimeout(long timeoutMs);
    
    /**
     * Sets retry policy for failed batch operations.
     * 
     * @param maxRetries Maximum number of retries
     * @param retryDelayMs Delay between retries in milliseconds
     * @return This instance for method chaining
     */
    BatchOperations withRetryPolicy(int maxRetries, long retryDelayMs);
    
    // ====================
    // Monitoring Methods
    // ====================
    
    /**
     * Gets statistics for the current batch operation.
     * 
     * @return Batch operation statistics
     */
    BatchStatistics getStatistics();
    
    /**
     * Registers a progress listener for batch operations.
     * 
     * @param listener Progress listener
     * @return This instance for method chaining
     */
    BatchOperations onProgress(BatchProgressListener listener);
    
    /**
     * Transaction isolation levels for batch operations.
     */
    enum TransactionIsolation {
        READ_UNCOMMITTED,
        READ_COMMITTED,
        REPEATABLE_READ,
        SERIALIZABLE
    }
    
    /**
     * Interface for monitoring batch operation progress.
     */
    interface BatchProgressListener {
        /**
         * Called when a batch is completed.
         * 
         * @param batchNumber The batch number
         * @param totalBatches Total number of batches
         * @param recordsProcessed Records processed in this batch
         */
        void onBatchCompleted(int batchNumber, int totalBatches, int recordsProcessed);
        
        /**
         * Called when a batch fails.
         * 
         * @param batchNumber The batch number
         * @param error The error that occurred
         */
        void onBatchFailed(int batchNumber, Throwable error);
    }
    
    /**
     * Statistics for batch operations.
     */
    interface BatchStatistics {
        long getTotalRecords();
        long getProcessedRecords();
        long getFailedRecords();
        long getElapsedTimeMs();
        double getRecordsPerSecond();
        Map<String, Long> getOperationCounts();
    }
}