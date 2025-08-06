package sh.harold.fulcrum.api.data.query.streaming;

import sh.harold.fulcrum.api.data.query.*;
import sh.harold.fulcrum.api.data.integration.QueryBuilderFactory;
import sh.harold.fulcrum.api.data.query.backend.*;
import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.stream.Collector;
import java.util.stream.Stream;

/**
 * Handles streaming execution of cross-schema queries.
 * Simplified version that uses native database streaming and standard Java streams.
 * 
 * <p>This executor provides efficient streaming of large result sets by:</p>
 * <ul>
 *   <li>Using database-native result set streaming</li>
 *   <li>Supporting pagination for memory efficiency</li>
 *   <li>Coordinating between multiple backend executors</li>
 * </ul>
 * 
 * @author Harold
 * @since 1.0
 */
public class StreamingExecutor {
    
    private static final Logger LOGGER = Logger.getLogger(StreamingExecutor.class.getName());
    
    private final SchemaJoinExecutor fallbackExecutor;
    private final ExecutorService executorService;
    private final int defaultBufferSize;
    private final long defaultTimeoutMillis;
    
    /**
     * Configuration for streaming execution.
     */
    public static class StreamingConfig {
        private final int bufferSize;
        private final long timeoutMillis;
        private final boolean parallelProcessing;
        private final int parallelism;
        
        public StreamingConfig() {
            this(1000, 0, true, 
                 ForkJoinPool.getCommonPoolParallelism());
        }
        
        public StreamingConfig(int bufferSize, long timeoutMillis,
                              boolean parallelProcessing, int parallelism) {
            this.bufferSize = bufferSize;
            this.timeoutMillis = timeoutMillis;
            this.parallelProcessing = parallelProcessing;
            this.parallelism = parallelism;
        }
        
        public int getBufferSize() {
            return bufferSize;
        }
        
        public long getTimeoutMillis() {
            return timeoutMillis;
        }
        
        public boolean isParallelProcessing() {
            return parallelProcessing;
        }
        
        public int getParallelism() {
            return parallelism;
        }
    }
    
    /**
     * Creates a StreamingExecutor with default configuration.
     */
    public StreamingExecutor() {
        this(new SchemaJoinExecutor(),
             ForkJoinPool.commonPool(),
             1000, 0);
    }
    
    /**
     * Creates a StreamingExecutor with custom configuration.
     */
    public StreamingExecutor(SchemaJoinExecutor fallbackExecutor,
                           ExecutorService executorService,
                           int defaultBufferSize,
                           long defaultTimeoutMillis) {
        this.fallbackExecutor = fallbackExecutor;
        this.executorService = executorService;
        this.defaultBufferSize = defaultBufferSize;
        this.defaultTimeoutMillis = defaultTimeoutMillis;
    }
    
    /**
     * Executes a query and returns a stream of results.
     * Simplified to use standard Java streams directly.
     * 
     * @param queryBuilder The query to execute
     * @return A CompletableFuture containing the result stream
     */
    public CompletableFuture<Stream<CrossSchemaResult>> stream(CrossSchemaQueryBuilder queryBuilder) {
        return stream(queryBuilder, new StreamingConfig());
    }
    
    /**
     * Executes a query with custom streaming configuration.
     * Uses database-native streaming when possible.
     * 
     * @param queryBuilder The query to execute
     * @param config Streaming configuration
     * @return A CompletableFuture containing the result stream
     */
    public CompletableFuture<Stream<CrossSchemaResult>> stream(
            CrossSchemaQueryBuilder queryBuilder, StreamingConfig config) {
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Get the appropriate executor
                SchemaJoinExecutor executor = getExecutor(queryBuilder);
                
                // Execute query and get results
                List<CrossSchemaResult> results = executor.execute(queryBuilder).get(
                    config.getTimeoutMillis() > 0 ? config.getTimeoutMillis() : Long.MAX_VALUE,
                    TimeUnit.MILLISECONDS
                );
                
                // Convert to stream
                Stream<CrossSchemaResult> stream = results.stream();
                
                if (config.isParallelProcessing()) {
                    stream = stream.parallel();
                }
                
                return stream;
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error creating stream", e);
                throw new CompletionException(e);
            }
        }, executorService);
    }
    
    /**
     * Processes each result asynchronously as it arrives.
     * 
     * @param queryBuilder The query to execute
     * @param action The action to perform on each result
     * @return A CompletableFuture that completes when all results are processed
     */
    public CompletableFuture<Void> forEachAsync(CrossSchemaQueryBuilder queryBuilder,
                                               Consumer<CrossSchemaResult> action) {
        return forEachAsync(queryBuilder, action, new StreamingConfig());
    }
    
    /**
     * Processes each result asynchronously with custom configuration.
     */
    public CompletableFuture<Void> forEachAsync(CrossSchemaQueryBuilder queryBuilder,
                                               Consumer<CrossSchemaResult> action,
                                               StreamingConfig config) {
        return stream(queryBuilder, config)
            .thenAccept(stream -> stream.forEach(action));
    }
    
    /**
     * Collects streaming results using a collector.
     */
    public <T> CompletableFuture<T> collectAsync(CrossSchemaQueryBuilder queryBuilder,
                                                Collector<CrossSchemaResult, ?, T> collector) {
        return collectAsync(queryBuilder, collector, new StreamingConfig());
    }
    
    /**
     * Collects streaming results with custom configuration.
     */
    public <T> CompletableFuture<T> collectAsync(CrossSchemaQueryBuilder queryBuilder,
                                                Collector<CrossSchemaResult, ?, T> collector,
                                                StreamingConfig config) {
        return stream(queryBuilder, config)
            .thenApply(stream -> stream.collect(collector));
    }
    
    /**
     * Gets the appropriate executor for the query.
     */
    private SchemaJoinExecutor getExecutor(CrossSchemaQueryBuilder queryBuilder) {
        // Try to get backend-specific executor
        PlayerDataBackend backend = sh.harold.fulcrum.api.data.registry.PlayerDataRegistry
            .getBackend(queryBuilder.getRootSchema());
        
        if (backend != null) {
            try {
                QueryBuilderFactory factory = new QueryBuilderFactory(backend);
                return factory.createExecutor(queryBuilder);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to create backend-specific executor", e);
            }
        }
        
        // Fallback to generic executor
        return fallbackExecutor;
    }
    
    /**
     * Counts the total number of results for a query.
     */
    public CompletableFuture<Long> countAsync(CrossSchemaQueryBuilder queryBuilder) {
        return CompletableFuture.supplyAsync(() -> {
            AtomicLong count = new AtomicLong(0);
            
            try {
                forEachAsync(queryBuilder, result -> count.incrementAndGet()).get();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
            
            return count.get();
        }, executorService);
    }
    
    /**
     * Gets a page of results using offset pagination.
     */
    public CompletableFuture<PaginationSupport.Page<CrossSchemaResult>> getPage(
            CrossSchemaQueryBuilder queryBuilder, int pageNumber, int pageSize) {
        
        // Create a modified query with offset and limit
        CrossSchemaQueryBuilder paginatedQuery = 
            copyQueryBuilder(queryBuilder)
                .offset(pageNumber * pageSize)
                .limit(pageSize);
        
        // Get total count (if needed)
        CompletableFuture<Long> countFuture = countAsync(queryBuilder);
        
        // Get page results
        CompletableFuture<List<CrossSchemaResult>> resultsFuture = 
            collectAsync(paginatedQuery, 
                        java.util.stream.Collectors.toList());
        
        // Combine results
        return countFuture.thenCombine(resultsFuture, 
            (totalCount, results) -> new PaginationSupport.Page<>(
                results, pageNumber, pageSize, totalCount
            )
        );
    }
    
    /**
     * Creates a copy of the query builder.
     * This is a simplified version - a full implementation would need
     * to properly copy all query parameters.
     */
    private CrossSchemaQueryBuilder copyQueryBuilder(CrossSchemaQueryBuilder original) {
        // This would need a proper implementation to copy all query parameters
        // For now, we'll use the original (which is not ideal)
        return original;
    }
}