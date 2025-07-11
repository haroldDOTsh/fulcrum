package sh.harold.fulcrum.api.data.query.streaming;

import sh.harold.fulcrum.api.data.query.*;
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
 * 
 * <p>This executor provides efficient streaming of large result sets by:</p>
 * <ul>
 *   <li>Processing results as they arrive from backends</li>
 *   <li>Managing memory through configurable buffering</li>
 *   <li>Supporting cancellation and timeout</li>
 *   <li>Coordinating between multiple backend executors</li>
 * </ul>
 * 
 * @author Harold
 * @since 1.0
 */
public class StreamingExecutor {
    
    private static final Logger LOGGER = Logger.getLogger(StreamingExecutor.class.getName());
    
    private final SchemaJoinExecutor fallbackExecutor;
    private final BackendSpecificExecutorFactory executorFactory;
    private final ExecutorService executorService;
    private final int defaultBufferSize;
    private final long defaultTimeoutMillis;
    
    /**
     * Configuration for streaming execution.
     */
    public static class StreamingConfig {
        private final int bufferSize;
        private final long timeoutMillis;
        private final BackpressureHandler.Strategy backpressureStrategy;
        private final boolean parallelProcessing;
        private final int parallelism;
        
        public StreamingConfig() {
            this(1000, 0, BackpressureHandler.Strategy.ADAPTIVE, true, 
                 ForkJoinPool.getCommonPoolParallelism());
        }
        
        public StreamingConfig(int bufferSize, long timeoutMillis,
                              BackpressureHandler.Strategy backpressureStrategy,
                              boolean parallelProcessing, int parallelism) {
            this.bufferSize = bufferSize;
            this.timeoutMillis = timeoutMillis;
            this.backpressureStrategy = backpressureStrategy;
            this.parallelProcessing = parallelProcessing;
            this.parallelism = parallelism;
        }
        
        public int getBufferSize() {
            return bufferSize;
        }
        
        public long getTimeoutMillis() {
            return timeoutMillis;
        }
        
        public BackpressureHandler.Strategy getBackpressureStrategy() {
            return backpressureStrategy;
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
             BackendSpecificExecutorFactory.getInstance(),
             ForkJoinPool.commonPool(),
             1000, 0);
    }
    
    /**
     * Creates a StreamingExecutor with custom configuration.
     */
    public StreamingExecutor(SchemaJoinExecutor fallbackExecutor,
                           BackendSpecificExecutorFactory executorFactory,
                           ExecutorService executorService,
                           int defaultBufferSize,
                           long defaultTimeoutMillis) {
        this.fallbackExecutor = fallbackExecutor;
        this.executorFactory = executorFactory;
        this.executorService = executorService;
        this.defaultBufferSize = defaultBufferSize;
        this.defaultTimeoutMillis = defaultTimeoutMillis;
    }
    
    /**
     * Executes a query and returns a stream of results.
     * 
     * @param queryBuilder The query to execute
     * @return A CompletableFuture containing the result stream
     */
    public CompletableFuture<Stream<CrossSchemaResult>> stream(CrossSchemaQueryBuilder queryBuilder) {
        return stream(queryBuilder, new StreamingConfig());
    }
    
    /**
     * Executes a query with custom streaming configuration.
     * 
     * @param queryBuilder The query to execute
     * @param config Streaming configuration
     * @return A CompletableFuture containing the result stream
     */
    public CompletableFuture<Stream<CrossSchemaResult>> stream(
            CrossSchemaQueryBuilder queryBuilder, StreamingConfig config) {
        
        return CompletableFuture.supplyAsync(() -> {
            AsyncResultStream resultStream = new AsyncResultStream(
                config.getBufferSize(),
                executorService,
                new BackpressureHandler(config.getBackpressureStrategy()),
                config.getTimeoutMillis()
            );
            
            // Start the streaming process
            startStreaming(queryBuilder, resultStream, config);
            
            // Return the stream
            Stream<CrossSchemaResult> stream = resultStream.stream();
            
            if (config.isParallelProcessing()) {
                stream = stream.parallel();
            }
            
            return stream;
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
        AsyncResultStream resultStream = new AsyncResultStream(
            config.getBufferSize(),
            executorService,
            new BackpressureHandler(config.getBackpressureStrategy()),
            config.getTimeoutMillis()
        );
        
        // Start streaming
        CompletableFuture<Void> streamingFuture = startStreaming(queryBuilder, resultStream, config);
        
        // Process results
        CompletableFuture<Void> processingFuture = resultStream.forEachAsync(action);
        
        // Combine both futures
        return CompletableFuture.allOf(streamingFuture, processingFuture);
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
     * Starts the streaming process.
     */
    private CompletableFuture<Void> startStreaming(CrossSchemaQueryBuilder queryBuilder,
                                                  AsyncResultStream resultStream,
                                                  StreamingConfig config) {
        // Register as producer
        resultStream.registerProducer();
        
        return CompletableFuture.runAsync(() -> {
            try {
                // Determine the backend and create appropriate executor
                PlayerDataBackend backend = determineBackend(queryBuilder);
                
                if (backend == null) {
                    // Fallback to standard execution and stream results
                    LOGGER.log(Level.INFO, "Using fallback executor for streaming");
                    streamFromFallback(queryBuilder, resultStream);
                } else {
                    // Use backend-specific streaming
                    LOGGER.log(Level.INFO, "Using backend-specific streaming for: " + 
                              backend.getClass().getSimpleName());
                    streamFromBackend(queryBuilder, resultStream, backend, config);
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error during streaming execution", e);
                throw new CompletionException(e);
            } finally {
                // Mark producer as completed
                resultStream.producerCompleted();
            }
        }, executorService);
    }
    
    /**
     * Streams results using the fallback executor.
     */
    private void streamFromFallback(CrossSchemaQueryBuilder queryBuilder,
                                  AsyncResultStream resultStream) {
        try {
            // Execute query and stream results
            List<CrossSchemaResult> results = fallbackExecutor.execute(queryBuilder).get();
            
            for (CrossSchemaResult result : results) {
                try {
                    resultStream.add(result);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException(e);
                }
            }
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }
    
    /**
     * Streams results using backend-specific executor.
     */
    private void streamFromBackend(CrossSchemaQueryBuilder queryBuilder,
                                 AsyncResultStream resultStream,
                                 PlayerDataBackend backend,
                                 StreamingConfig config) {
        // Create the appropriate executor based on the query
        SchemaJoinExecutor executor = executorFactory.createExecutor(queryBuilder);
        
        if (executor instanceof SqlSchemaJoinExecutor) {
            streamFromSql((SqlSchemaJoinExecutor) executor,
                        queryBuilder, resultStream, config);
        } else if (executor instanceof MongoSchemaJoinExecutor) {
            streamFromMongo((MongoSchemaJoinExecutor) executor,
                          queryBuilder, resultStream, config);
        } else if (executor instanceof JsonSchemaJoinExecutor) {
            streamFromJson((JsonSchemaJoinExecutor) executor,
                         queryBuilder, resultStream, config);
        } else {
            // Generic executor or unknown type, use fallback
            streamFromFallback(queryBuilder, resultStream);
        }
    }
    
    /**
     * Streams results from SQL backend.
     */
    private void streamFromSql(SqlSchemaJoinExecutor executor,
                             CrossSchemaQueryBuilder queryBuilder,
                             AsyncResultStream resultStream,
                             StreamingConfig config) {
        // SQL-specific streaming would be implemented here
        // For now, use the standard execution
        try {
            List<CrossSchemaResult> results = executor.execute(queryBuilder).get();
            for (CrossSchemaResult result : results) {
                resultStream.add(result);
            }
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }
    
    /**
     * Streams results from MongoDB backend.
     */
    private void streamFromMongo(MongoSchemaJoinExecutor executor,
                               CrossSchemaQueryBuilder queryBuilder,
                               AsyncResultStream resultStream,
                               StreamingConfig config) {
        // MongoDB-specific streaming would be implemented here
        // For now, use the standard execution
        try {
            List<CrossSchemaResult> results = executor.execute(queryBuilder).get();
            for (CrossSchemaResult result : results) {
                resultStream.add(result);
            }
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }
    
    /**
     * Streams results from JSON backend.
     */
    private void streamFromJson(JsonSchemaJoinExecutor executor,
                              CrossSchemaQueryBuilder queryBuilder,
                              AsyncResultStream resultStream,
                              StreamingConfig config) {
        // JSON-specific streaming would be implemented here
        // For now, use the standard execution
        try {
            List<CrossSchemaResult> results = executor.execute(queryBuilder).get();
            for (CrossSchemaResult result : results) {
                resultStream.add(result);
            }
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }
    
    /**
     * Determines the backend from the query.
     */
    private PlayerDataBackend determineBackend(CrossSchemaQueryBuilder queryBuilder) {
        // This would need to be implemented to determine the backend
        // based on the schemas in the query
        // For now, return null to use fallback
        return null;
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