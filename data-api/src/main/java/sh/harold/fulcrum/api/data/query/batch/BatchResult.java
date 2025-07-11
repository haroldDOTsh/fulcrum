package sh.harold.fulcrum.api.data.query.batch;

import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents the result of a batch operation across multiple schemas.
 * Provides detailed information about the operation's success, failures, and performance metrics.
 *
 * <p>This class is thread-safe and can be used to track progress of concurrent batch operations.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * BatchResult result = batchOperations.batchUpdate(uuids, updates).get();
 *
 * if (result.isFullySuccessful()) {
 *     System.out.println("All records updated successfully");
 * } else {
 *     System.out.println("Failed records: " + result.getFailedCount());
 *     result.getFailures().forEach((uuid, error) -> {
 *         System.err.println("Failed to update " + uuid + ": " + error.getMessage());
 *     });
 * }
 * }</pre>
 *
 * @param <T> The type of data being processed
 * @author Harold
 * @since 1.0
 */
public class BatchResult<T> {
    
    private final BatchOperationType operationType;
    private final Instant startTime;
    private final AtomicLong successCount;
    private final AtomicLong failureCount;
    private final AtomicLong skippedCount;
    private final Map<UUID, Throwable> failures;
    private final Map<PlayerDataSchema<?>, SchemaStats> schemaStats;
    private final List<String> warnings;
    private volatile Instant endTime;
    private volatile boolean completed;
    private volatile BatchStatus status;
    private final Map<String, Object> metadata;
    
    /**
     * Creates a new batch result for the specified operation type.
     * 
     * @param operationType The type of batch operation
     */
    public BatchResult(BatchOperationType operationType) {
        this.operationType = Objects.requireNonNull(operationType, "Operation type cannot be null");
        this.startTime = Instant.now();
        this.successCount = new AtomicLong(0);
        this.failureCount = new AtomicLong(0);
        this.skippedCount = new AtomicLong(0);
        this.failures = new ConcurrentHashMap<>();
        this.schemaStats = new ConcurrentHashMap<>();
        this.warnings = Collections.synchronizedList(new ArrayList<>());
        this.status = BatchStatus.IN_PROGRESS;
        this.metadata = new ConcurrentHashMap<>();
    }
    
    /**
     * Records a successful operation for a UUID.
     * 
     * @param uuid The UUID that was successfully processed
     * @param schema The schema that was affected
     */
    public void recordSuccess(UUID uuid, PlayerDataSchema<?> schema) {
        successCount.incrementAndGet();
        getOrCreateSchemaStats(schema).recordSuccess();
    }
    
    /**
     * Records a failed operation for a UUID.
     * 
     * @param uuid The UUID that failed to process
     * @param schema The schema that was affected
     * @param error The error that occurred
     */
    public void recordFailure(UUID uuid, PlayerDataSchema<?> schema, Throwable error) {
        failureCount.incrementAndGet();
        failures.put(uuid, error);
        getOrCreateSchemaStats(schema).recordFailure();
    }
    
    /**
     * Records a skipped operation for a UUID.
     * 
     * @param uuid The UUID that was skipped
     * @param schema The schema that was affected
     * @param reason The reason for skipping
     */
    public void recordSkipped(UUID uuid, PlayerDataSchema<?> schema, String reason) {
        skippedCount.incrementAndGet();
        getOrCreateSchemaStats(schema).recordSkipped();
        if (reason != null) {
            warnings.add(String.format("Skipped UUID %s: %s", uuid, reason));
        }
    }
    
    /**
     * Adds a warning message to the result.
     * 
     * @param warning The warning message
     */
    public void addWarning(String warning) {
        warnings.add(warning);
    }
    
    /**
     * Marks the batch operation as completed.
     */
    public void complete() {
        complete(BatchStatus.COMPLETED);
    }
    
    /**
     * Marks the batch operation as completed with a specific status.
     * 
     * @param status The final status
     */
    public void complete(BatchStatus status) {
        this.endTime = Instant.now();
        this.completed = true;
        this.status = status;
    }
    
    /**
     * Gets the type of batch operation.
     * 
     * @return The operation type
     */
    public BatchOperationType getOperationType() {
        return operationType;
    }
    
    /**
     * Gets the number of successful operations.
     * 
     * @return The success count
     */
    public long getSuccessCount() {
        return successCount.get();
    }
    
    /**
     * Gets the number of failed operations.
     * 
     * @return The failure count
     */
    public long getFailureCount() {
        return failureCount.get();
    }
    
    /**
     * Gets the number of skipped operations.
     * 
     * @return The skipped count
     */
    public long getSkippedCount() {
        return skippedCount.get();
    }
    
    /**
     * Gets the total number of operations processed.
     * 
     * @return The total count
     */
    public long getTotalCount() {
        return successCount.get() + failureCount.get() + skippedCount.get();
    }
    
    /**
     * Checks if all operations were successful.
     * 
     * @return true if no failures occurred
     */
    public boolean isFullySuccessful() {
        return completed && failureCount.get() == 0 && status == BatchStatus.COMPLETED;
    }
    
    /**
     * Checks if the operation is partially successful.
     * 
     * @return true if some operations succeeded and some failed
     */
    public boolean isPartiallySuccessful() {
        return completed && successCount.get() > 0 && failureCount.get() > 0;
    }
    
    /**
     * Gets the map of failures by UUID.
     * 
     * @return An unmodifiable map of UUID to error
     */
    public Map<UUID, Throwable> getFailures() {
        return Collections.unmodifiableMap(failures);
    }
    
    /**
     * Gets statistics for each schema.
     * 
     * @return An unmodifiable map of schema to statistics
     */
    public Map<PlayerDataSchema<?>, SchemaStats> getSchemaStats() {
        return Collections.unmodifiableMap(schemaStats);
    }
    
    /**
     * Gets all warning messages.
     * 
     * @return An unmodifiable list of warnings
     */
    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }
    
    /**
     * Gets the duration of the batch operation.
     * 
     * @return The duration, or empty if not completed
     */
    public Optional<Duration> getDuration() {
        if (endTime == null) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(startTime, endTime));
    }
    
    /**
     * Gets the current status of the batch operation.
     * 
     * @return The current status
     */
    public BatchStatus getStatus() {
        return status;
    }
    
    /**
     * Checks if the batch operation is completed.
     * 
     * @return true if completed
     */
    public boolean isCompleted() {
        return completed;
    }
    
    /**
     * Gets the start time of the operation.
     * 
     * @return The start time
     */
    public Instant getStartTime() {
        return startTime;
    }
    
    /**
     * Gets the end time of the operation.
     * 
     * @return The end time, or empty if not completed
     */
    public Optional<Instant> getEndTime() {
        return Optional.ofNullable(endTime);
    }
    
    /**
     * Adds metadata to the result.
     * 
     * @param key The metadata key
     * @param value The metadata value
     */
    public void addMetadata(String key, Object value) {
        metadata.put(key, value);
    }
    
    /**
     * Gets metadata by key.
     * 
     * @param key The metadata key
     * @return The metadata value, or empty if not present
     */
    public Optional<Object> getMetadata(String key) {
        return Optional.ofNullable(metadata.get(key));
    }
    
    /**
     * Gets all metadata.
     * 
     * @return An unmodifiable map of metadata
     */
    public Map<String, Object> getAllMetadata() {
        return Collections.unmodifiableMap(metadata);
    }
    
    /**
     * Merges another batch result into this one.
     * Useful for combining results from parallel batch operations.
     * 
     * @param other The other batch result to merge
     */
    public void merge(BatchResult other) {
        if (!this.operationType.equals(other.operationType)) {
            throw new IllegalArgumentException("Cannot merge results from different operation types");
        }
        
        this.successCount.addAndGet(other.successCount.get());
        this.failureCount.addAndGet(other.failureCount.get());
        this.skippedCount.addAndGet(other.skippedCount.get());
        this.failures.putAll(other.failures);
        this.warnings.addAll(other.warnings);
        
        // Merge schema stats
        other.schemaStats.forEach((schema, stats) -> {
            SchemaStats existingStats = getOrCreateSchemaStats((PlayerDataSchema<?>) schema);
            existingStats.merge((SchemaStats) stats);
        });
        
        // Merge metadata
        this.metadata.putAll(other.metadata);
    }
    
    @Override
    public String toString() {
        return String.format("BatchResult[type=%s, success=%d, failed=%d, skipped=%d, status=%s, duration=%s]",
            operationType, successCount.get(), failureCount.get(), skippedCount.get(),
            status, getDuration().map(d -> d.toMillis() + "ms").orElse("in-progress"));
    }
    
    private SchemaStats getOrCreateSchemaStats(PlayerDataSchema<?> schema) {
        return schemaStats.computeIfAbsent(schema, k -> new SchemaStats());
    }
    
    /**
     * Types of batch operations.
     */
    public enum BatchOperationType {
        LOAD("Load"),
        UPDATE("Update"),
        DELETE("Delete"),
        INSERT("Insert"),
        UPSERT("Upsert"),
        APPLY("Apply"),
        CUSTOM("Custom");
        
        private final String displayName;
        
        BatchOperationType(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
    
    /**
     * Status of a batch operation.
     */
    public enum BatchStatus {
        IN_PROGRESS,
        COMPLETED,
        COMPLETED_WITH_ERRORS,
        FAILED,
        CANCELLED,
        TIMEOUT
    }
    
    /**
     * Statistics for a single schema within a batch operation.
     */
    public static class SchemaStats {
        private final AtomicLong successCount = new AtomicLong(0);
        private final AtomicLong failureCount = new AtomicLong(0);
        private final AtomicLong skippedCount = new AtomicLong(0);
        
        void recordSuccess() {
            successCount.incrementAndGet();
        }
        
        void recordFailure() {
            failureCount.incrementAndGet();
        }
        
        void recordSkipped() {
            skippedCount.incrementAndGet();
        }
        
        void merge(SchemaStats other) {
            this.successCount.addAndGet(other.successCount.get());
            this.failureCount.addAndGet(other.failureCount.get());
            this.skippedCount.addAndGet(other.skippedCount.get());
        }
        
        public long getSuccessCount() {
            return successCount.get();
        }
        
        public long getFailureCount() {
            return failureCount.get();
        }
        
        public long getSkippedCount() {
            return skippedCount.get();
        }
        
        public long getTotalCount() {
            return successCount.get() + failureCount.get() + skippedCount.get();
        }
    }
}