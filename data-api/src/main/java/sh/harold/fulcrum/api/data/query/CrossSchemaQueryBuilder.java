package sh.harold.fulcrum.api.data.query;

import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.query.streaming.*;
import sh.harold.fulcrum.api.data.query.batch.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collector;
import java.util.stream.Stream;

/**
 * Main fluent interface for building cross-schema queries with UUID-based joins.
 *
 * <p>This builder provides a fluent API for constructing complex queries that span
 * multiple schemas, using UUID as the common join key. All operations are lazy-evaluated
 * and only executed when {@link #executeAsync()} or {@link #stream()} is called.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * CrossSchemaQueryBuilder
 *     .from(RankSchema.class)
 *     .where("rank", equals("MVP++"))
 *     .join(GuildSchema.class)
 *         .where("guild", equals("Titans"))
 *     .orderBy("kills", DESC)
 *     .limit(50)
 *     .executeAsync()
 *     .thenAccept(results -> {
 *         // Process results
 *     });
 * }</pre>
 *
 * @author Harold
 * @since 1.0
 */
public class CrossSchemaQueryBuilder {
    
    private final PlayerDataSchema<?> rootSchema;
    private final List<JoinOperation> joins;
    private final List<QueryFilter> filters;
    private final List<SortOrder> sortOrders;
    private Integer limit;
    private Integer offset;
    private String cursor;
    private Integer pageSize;
    private Integer bufferSize;
    private StreamingExecutor streamingExecutor;
    private BatchConfiguration batchConfig;
    private BatchExecutor batchExecutor;
    
    /**
     * Creates a new query builder with the specified root schema.
     *
     * @param rootSchema The primary schema to query from
     */
    private CrossSchemaQueryBuilder(PlayerDataSchema<?> rootSchema) {
        this.rootSchema = Objects.requireNonNull(rootSchema, "Root schema cannot be null");
        this.joins = new ArrayList<>();
        this.filters = new ArrayList<>();
        this.sortOrders = new ArrayList<>();
    }
    
    /**
     * Creates a new query builder starting from the specified schema.
     * 
     * @param schemaClass The class representing the root schema
     * @param <T> The type of the schema
     * @return A new CrossSchemaQueryBuilder instance
     */
    public static <T> CrossSchemaQueryBuilder from(Class<? extends PlayerDataSchema<T>> schemaClass) {
        try {
            PlayerDataSchema<T> schema = schemaClass.getDeclaredConstructor().newInstance();
            return new CrossSchemaQueryBuilder(schema);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to instantiate schema: " + schemaClass.getName(), e);
        }
    }
    
    /**
     * Creates a new query builder starting from the specified schema instance.
     * 
     * @param schema The root schema instance
     * @param <T> The type of the schema
     * @return A new CrossSchemaQueryBuilder instance
     */
    public static <T> CrossSchemaQueryBuilder from(PlayerDataSchema<T> schema) {
        return new CrossSchemaQueryBuilder(schema);
    }
    
    /**
     * Adds a filter condition to the query.
     * 
     * @param field The field name to filter on
     * @param predicate The predicate to apply
     * @return This builder for method chaining
     */
    public CrossSchemaQueryBuilder where(String field, Predicate<?> predicate) {
        return where(field, predicate, rootSchema);
    }
    
    /**
     * Adds a filter condition to the query for a specific schema.
     *
     * @param field The field name to filter on
     * @param predicate The predicate to apply
     * @param schema The schema this filter applies to
     * @return This builder for method chaining
     */
    public CrossSchemaQueryBuilder where(String field, Predicate<?> predicate, PlayerDataSchema<?> schema) {
        filters.add(new QueryFilter(field, predicate, schema));
        return this;
    }
    
    /**
     * Adds a filter condition to the query using an existing QueryFilter.
     * This method preserves operator information for SQL compatibility.
     *
     * @param filter The QueryFilter to add
     * @return This builder for method chaining
     */
    public CrossSchemaQueryBuilder where(QueryFilter filter) {
        if (filter != null) {
            filters.add(filter);
        }
        return this;
    }
    
    /**
     * Adds a filter condition using a lambda expression.
     * 
     * @param filterFunction A function that creates the filter
     * @param <T> The type of the schema data
     * @return This builder for method chaining
     */
    public <T> CrossSchemaQueryBuilder where(Function<T, Boolean> filterFunction) {
        // This would require runtime type information, implement with reflection if needed
        throw new UnsupportedOperationException("Lambda-based filtering not yet implemented");
    }
    
    /**
     * Joins another schema to the query using UUID as the join key.
     * 
     * @param schemaClass The schema class to join
     * @param <T> The type of the schema
     * @return A JoinBuilder for configuring the join
     */
    public <T> JoinBuilder join(Class<? extends PlayerDataSchema<T>> schemaClass) {
        try {
            PlayerDataSchema<T> schema = schemaClass.getDeclaredConstructor().newInstance();
            return new JoinBuilder(this, schema);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to instantiate schema: " + schemaClass.getName(), e);
        }
    }
    
    /**
     * Joins another schema to the query using UUID as the join key.
     * 
     * @param schema The schema instance to join
     * @param <T> The type of the schema
     * @return A JoinBuilder for configuring the join
     */
    public <T> JoinBuilder join(PlayerDataSchema<T> schema) {
        return new JoinBuilder(this, schema);
    }
    
    /**
     * Orders the results by the specified field.
     * 
     * @param field The field to order by
     * @param direction The sort direction (ASC or DESC)
     * @return This builder for method chaining
     */
    public CrossSchemaQueryBuilder orderBy(String field, SortOrder.Direction direction) {
        sortOrders.add(new SortOrder(field, direction, rootSchema));
        return this;
    }
    
    /**
     * Orders the results by the specified field in ascending order.
     * 
     * @param field The field to order by
     * @return This builder for method chaining
     */
    public CrossSchemaQueryBuilder orderBy(String field) {
        return orderBy(field, SortOrder.Direction.ASC);
    }
    
    /**
     * Limits the number of results returned.
     * 
     * @param limit The maximum number of results
     * @return This builder for method chaining
     */
    public CrossSchemaQueryBuilder limit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Limit must be positive");
        }
        this.limit = limit;
        return this;
    }
    
    /**
     * Sets the offset for pagination.
     * 
     * @param offset The number of results to skip
     * @return This builder for method chaining
     */
    public CrossSchemaQueryBuilder offset(int offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("Offset cannot be negative");
        }
        this.offset = offset;
        return this;
    }
    
    /**
     * Executes the query asynchronously and returns the results.
     * 
     * @return A CompletableFuture containing the query results
     */
    public CompletableFuture<List<CrossSchemaResult>> executeAsync() {
        // Create the executor and execute the query
        SchemaJoinExecutor executor = new SchemaJoinExecutor();
        return executor.execute(this);
    }
    
    /**
     * Executes the query and returns a stream of results.
     * This allows for lazy processing of large result sets with async support.
     *
     * @return A CompletableFuture containing a Stream of CrossSchemaResult objects
     */
    public CompletableFuture<Stream<CrossSchemaResult>> stream() {
        StreamingExecutor executor = getStreamingExecutor();
        StreamingExecutor.StreamingConfig config = createStreamingConfig();
        return executor.stream(this, config);
    }
    
    /**
     * Executes the query and processes results asynchronously with the given consumer.
     *
     * @param resultConsumer The consumer to process each result
     * @return A CompletableFuture that completes when all results are processed
     */
    public CompletableFuture<Void> forEachAsync(Consumer<CrossSchemaResult> resultConsumer) {
        StreamingExecutor executor = getStreamingExecutor();
        StreamingExecutor.StreamingConfig config = createStreamingConfig();
        return executor.forEachAsync(this, resultConsumer, config);
    }
    
    /**
     * Collects query results asynchronously using the provided collector.
     *
     * @param collector The collector to use for aggregating results
     * @param <T> The type of the result
     * @return A CompletableFuture containing the collected result
     */
    public <T> CompletableFuture<T> collectAsync(Collector<CrossSchemaResult, ?, T> collector) {
        StreamingExecutor executor = getStreamingExecutor();
        StreamingExecutor.StreamingConfig config = createStreamingConfig();
        return executor.collectAsync(this, collector, config);
    }
    
    // Public getters for query execution by backend-specific executors
    
    /**
     * Gets the root schema for this query.
     *
     * @return The root schema
     */
    public PlayerDataSchema<?> getRootSchema() {
        return rootSchema;
    }
    
    /**
     * Gets the list of join operations for this query.
     *
     * @return An unmodifiable list of join operations
     */
    public List<JoinOperation> getJoins() {
        return Collections.unmodifiableList(joins);
    }
    
    /**
     * Gets the list of filters for this query.
     *
     * @return An unmodifiable list of filters
     */
    public List<QueryFilter> getFilters() {
        return Collections.unmodifiableList(filters);
    }
    
    /**
     * Gets the list of sort orders for this query.
     *
     * @return An unmodifiable list of sort orders
     */
    public List<SortOrder> getSortOrders() {
        return Collections.unmodifiableList(sortOrders);
    }
    
    /**
     * Gets the limit for this query.
     *
     * @return An optional containing the limit, or empty if no limit is set
     */
    public Optional<Integer> getLimit() {
        return Optional.ofNullable(limit);
    }
    
    /**
     * Gets the offset for this query.
     *
     * @return An optional containing the offset, or empty if no offset is set
     */
    public Optional<Integer> getOffset() {
        return Optional.ofNullable(offset);
    }
    
    /**
     * Sets the cursor for cursor-based pagination.
     *
     * @param cursor The cursor string
     * @return This builder for method chaining
     */
    public CrossSchemaQueryBuilder after(String cursor) {
        this.cursor = cursor;
        return this;
    }
    
    /**
     * Sets the page size for pagination.
     *
     * @param size The page size
     * @return This builder for method chaining
     */
    public CrossSchemaQueryBuilder pageSize(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Page size must be positive");
        }
        this.pageSize = size;
        return this;
    }
    
    /**
     * Gets a specific page of results.
     *
     * @param pageNumber The page number (0-based)
     * @param pageSize The page size
     * @return A CompletableFuture containing the page
     */
    public CompletableFuture<PaginationSupport.Page<CrossSchemaResult>> getPage(int pageNumber, int pageSize) {
        StreamingExecutor executor = getStreamingExecutor();
        return executor.getPage(this, pageNumber, pageSize);
    }
    
    /**
     * Counts the total number of results asynchronously.
     *
     * @return A CompletableFuture containing the count
     */
    public CompletableFuture<Long> countAsync() {
        StreamingExecutor executor = getStreamingExecutor();
        return executor.countAsync(this);
    }
    
    /**
     * Sets the buffer size for streaming operations.
     *
     * @param size The buffer size
     * @return This builder for method chaining
     */
    public CrossSchemaQueryBuilder bufferSize(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size must be positive");
        }
        this.bufferSize = size;
        return this;
    }
    
    
    /**
     * Gets the cursor for this query.
     *
     * @return An optional containing the cursor, or empty if not set
     */
    public Optional<String> getCursor() {
        return Optional.ofNullable(cursor);
    }
    
    /**
     * Gets the page size for this query.
     *
     * @return An optional containing the page size, or empty if not set
     */
    public Optional<Integer> getPageSize() {
        return Optional.ofNullable(pageSize);
    }
    
    /**
     * Gets the buffer size for this query.
     *
     * @return An optional containing the buffer size, or empty if not set
     */
    public Optional<Integer> getBufferSize() {
        return Optional.ofNullable(bufferSize);
    }
    
    
    void addJoin(JoinOperation join) {
        joins.add(join);
    }
    
    // ========================
    // Batch Operation Methods
    // ========================
    
    /**
     * Creates a batch operations interface for this query.
     * This allows efficient batch processing of query results.
     *
     * @return A BatchOperations instance configured for this query
     */
    public BatchOperations batch() {
        if (batchExecutor == null) {
            BatchConfiguration config = batchConfig != null ? batchConfig : BatchConfiguration.defaultConfig();
            batchExecutor = new BatchExecutor(this, config);
        }
        return batchExecutor;
    }
    
    /**
     * Configures batch operations with custom settings.
     *
     * @param config The batch configuration
     * @return This builder for method chaining
     */
    public CrossSchemaQueryBuilder withBatchConfig(BatchConfiguration config) {
        this.batchConfig = Objects.requireNonNull(config, "Batch configuration cannot be null");
        return this;
    }
    
    /**
     * Sets the batch size for batch operations.
     * Convenience method that creates a default batch configuration with the specified size.
     *
     * @param size The batch size
     * @return This builder for method chaining
     */
    public CrossSchemaQueryBuilder batchSize(int size) {
        if (batchConfig == null) {
            batchConfig = BatchConfiguration.defaultConfig();
        }
        batchConfig.setBatchSize(size);
        return this;
    }
    
    /**
     * Enables parallel batch processing with specified concurrency.
     *
     * @param concurrency Number of concurrent batches to process
     * @return This builder for method chaining
     */
    public CrossSchemaQueryBuilder parallelBatches(int concurrency) {
        if (batchConfig == null) {
            batchConfig = BatchConfiguration.defaultConfig();
        }
        batchConfig.setParallelism(concurrency);
        return this;
    }
    
    /**
     * Enables connection pooling for database operations.
     *
     * @param maxConnections Maximum number of pooled connections
     * @return This builder for method chaining
     */
    public CrossSchemaQueryBuilder withConnectionPooling(int maxConnections) {
        if (batchConfig == null) {
            batchConfig = BatchConfiguration.defaultConfig();
        }
        batchConfig.setMaxConnections(maxConnections);
        return this;
    }
    
    /**
     * Enables prepared statement caching.
     *
     * @param maxCacheSize Maximum number of cached statements
     * @return This builder for method chaining
     */
    public CrossSchemaQueryBuilder withPreparedStatementCache(int maxCacheSize) {
        if (batchConfig == null) {
            batchConfig = BatchConfiguration.defaultConfig();
        }
        batchConfig.setPreparedStatementCacheSize(maxCacheSize);
        return this;
    }
    
    /**
     * Enables memory pooling for object reuse.
     *
     * @param enabled Whether to enable memory pooling
     * @return This builder for method chaining
     */
    public CrossSchemaQueryBuilder withMemoryPooling(boolean enabled) {
        if (batchConfig == null) {
            batchConfig = BatchConfiguration.defaultConfig();
        }
        batchConfig.setMemoryPoolingEnabled(enabled);
        return this;
    }
    
    /**
     * Enables database-specific bulk operations.
     *
     * @param enabled Whether to enable bulk operations
     * @return This builder for method chaining
     */
    public CrossSchemaQueryBuilder withBulkOperations(boolean enabled) {
        if (batchConfig == null) {
            batchConfig = BatchConfiguration.defaultConfig();
        }
        batchConfig.setBulkOperationsEnabled(enabled);
        return this;
    }
    
    /**
     * Executes the query in batches and returns all results.
     * This is a convenience method that uses the batch executor directly.
     *
     * @return A CompletableFuture containing all query results
     */
    public CompletableFuture<List<CrossSchemaResult>> executeInBatches() {
        return batch().executeInBatches(batchConfig != null ? batchConfig.getParallelism() :
                                        Runtime.getRuntime().availableProcessors());
    }
    
    /**
     * Creates a batch transaction for this query.
     * Allows transactional batch operations on the query results.
     *
     * @return A new BatchTransaction instance
     */
    public BatchTransaction beginBatchTransaction() {
        return new BatchTransaction()
            .withTimeout(batchConfig != null ? batchConfig.getTimeoutMs() : 30000);
    }
    
    
    /**
     * Gets or creates the streaming executor.
     */
    private StreamingExecutor getStreamingExecutor() {
        if (streamingExecutor == null) {
            streamingExecutor = new StreamingExecutor();
        }
        return streamingExecutor;
    }
    
    /**
     * Creates streaming configuration from query parameters.
     */
    private StreamingExecutor.StreamingConfig createStreamingConfig() {
        int bufferSizeValue = bufferSize != null ? bufferSize : 1000;
        
        return new StreamingExecutor.StreamingConfig(
            bufferSizeValue,
            0, // no timeout by default
            true, // parallel processing
            Runtime.getRuntime().availableProcessors()
        );
    }
    
    /**
     * Builder for configuring join operations.
     */
    public static class JoinBuilder {
        private final CrossSchemaQueryBuilder parent;
        private final PlayerDataSchema<?> joinSchema;
        private final List<QueryFilter> joinFilters;
        
        private JoinBuilder(CrossSchemaQueryBuilder parent, PlayerDataSchema<?> joinSchema) {
            this.parent = parent;
            this.joinSchema = joinSchema;
            this.joinFilters = new ArrayList<>();
        }
        
        /**
         * Adds a filter condition to the join.
         *
         * @param field The field name to filter on
         * @param predicate The predicate to apply
         * @return This JoinBuilder for method chaining
         */
        public JoinBuilder where(String field, Predicate<?> predicate) {
            joinFilters.add(new QueryFilter(field, predicate, joinSchema));
            return this;
        }
        
        /**
         * Adds a filter condition to the join using an existing QueryFilter.
         * This method preserves operator information for SQL compatibility.
         *
         * @param filter The QueryFilter to add
         * @return This JoinBuilder for method chaining
         */
        public JoinBuilder where(QueryFilter filter) {
            if (filter != null) {
                joinFilters.add(filter);
            }
            return this;
        }
        
        /**
         * Completes the join configuration and returns to the main query builder.
         * 
         * @return The parent CrossSchemaQueryBuilder
         */
        public CrossSchemaQueryBuilder and() {
            parent.addJoin(new JoinOperation(joinSchema, joinFilters));
            return parent;
        }
        
        /**
         * Orders the results by the specified field.
         * Completes the join and adds ordering.
         * 
         * @param field The field to order by
         * @param direction The sort direction
         * @return The parent CrossSchemaQueryBuilder
         */
        public CrossSchemaQueryBuilder orderBy(String field, SortOrder.Direction direction) {
            and();
            return parent.orderBy(field, direction);
        }
        
        /**
         * Limits the number of results.
         * Completes the join and adds limit.
         * 
         * @param limit The maximum number of results
         * @return The parent CrossSchemaQueryBuilder
         */
        public CrossSchemaQueryBuilder limit(int limit) {
            and();
            return parent.limit(limit);
        }
        
        /**
         * Executes the query asynchronously.
         * Completes the join and executes.
         * 
         * @return A CompletableFuture containing the query results
         */
        public CompletableFuture<List<CrossSchemaResult>> executeAsync() {
            and();
            return parent.executeAsync();
        }
        
        /**
         * Joins another schema to the query.
         * Completes the current join and starts a new one.
         * 
         * @param schemaClass The schema class to join
         * @param <T> The type of the schema
         * @return A new JoinBuilder
         */
        public <T> JoinBuilder join(Class<? extends PlayerDataSchema<T>> schemaClass) {
            and();
            return parent.join(schemaClass);
        }
    }
}