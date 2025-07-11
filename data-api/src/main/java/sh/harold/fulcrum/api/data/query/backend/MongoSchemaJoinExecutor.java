package sh.harold.fulcrum.api.data.query.backend;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import org.bson.Document;
import org.bson.conversions.Bson;
import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.backend.mongo.MongoDataBackend;
import sh.harold.fulcrum.api.data.impl.JsonSchema;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.query.*;
import sh.harold.fulcrum.api.data.registry.PlayerDataRegistry;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * MongoDB-specific implementation of cross-schema query execution with optimizations
 * using aggregation pipelines for efficient joins and filtering.
 * 
 * <p>This executor provides the following optimizations:</p>
 * <ul>
 *   <li>MongoDB aggregation pipelines with $lookup for joins</li>
 *   <li>Efficient $match stages for filtering</li>
 *   <li>$project stages to limit returned fields</li>
 *   <li>Index hints for performance</li>
 *   <li>Bulk operations where possible</li>
 *   <li>Parallel processing for application-level joins</li>
 * </ul>
 * 
 * <p>When schemas are in different MongoDB databases or when complex joins are needed,
 * falls back to application-level UUID intersection using the parent SchemaJoinExecutor logic.</p>
 * 
 * @author Harold
 * @since 1.0
 */
public class MongoSchemaJoinExecutor extends SchemaJoinExecutor {
    
    private static final Logger LOGGER = Logger.getLogger(MongoSchemaJoinExecutor.class.getName());
    
    /**
     * Cache for schema collection information.
     */
    private final Map<PlayerDataSchema<?>, CollectionInfo> collectionInfoCache = new ConcurrentHashMap<>();
    
    /**
     * Creates a new MongoSchemaJoinExecutor with default components.
     */
    public MongoSchemaJoinExecutor() {
        super();
    }
    
    /**
     * Creates a new MongoSchemaJoinExecutor with specified executor service.
     * 
     * @param executorService The executor service for async operations
     */
    public MongoSchemaJoinExecutor(ExecutorService executorService) {
        super(executorService);
    }
    
    /**
     * Executes a cross-schema query with MongoDB-specific optimizations.
     * 
     * @param queryBuilder The query builder containing the query specification
     * @return A CompletableFuture containing the query results
     */
    @Override
    public CompletableFuture<List<CrossSchemaResult>> execute(CrossSchemaQueryBuilder queryBuilder) {
        LOGGER.log(Level.FINE, "Executing MongoDB-optimized cross-schema query for root schema: {0}", 
                   queryBuilder.getRootSchema().schemaKey());
        
        // Check if we can use MongoDB aggregation pipeline
        if (canUseAggregationPipeline(queryBuilder)) {
            return executeAggregationQuery(queryBuilder);
        } else {
            // Fall back to application-level join
            LOGGER.log(Level.FINE, "Cannot use aggregation pipeline, falling back to application-level join");
            return super.execute(queryBuilder);
        }
    }
    
    /**
     * Checks if all schemas support MongoDB aggregation pipeline joins.
     * Requirements:
     * - All schemas use MongoDataBackend
     * - All schemas are JsonSchema (MongoDB stores JSON)
     * - All collections are in the same database (for $lookup)
     */
    private boolean canUseAggregationPipeline(CrossSchemaQueryBuilder queryBuilder) {
        Set<PlayerDataSchema<?>> schemas = collectAllSchemas(queryBuilder);
        
        String firstDatabase = null;
        
        for (PlayerDataSchema<?> schema : schemas) {
            PlayerDataBackend backend = PlayerDataRegistry.getBackend(schema);
            
            if (!(backend instanceof MongoDataBackend)) {
                return false; // Not all backends are MongoDB
            }
            
            if (!(schema instanceof JsonSchema)) {
                return false; // MongoDB requires JsonSchema
            }
            
            CollectionInfo info = getCollectionInfo(schema, (MongoDataBackend) backend);
            
            if (firstDatabase == null) {
                firstDatabase = info.databaseName;
            } else if (!firstDatabase.equals(info.databaseName)) {
                return false; // Collections in different databases
            }
        }
        
        return true;
    }
    
    /**
     * Executes the query using MongoDB aggregation pipeline.
     */
    private CompletableFuture<List<CrossSchemaResult>> executeAggregationQuery(CrossSchemaQueryBuilder queryBuilder) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Build aggregation pipeline
                List<Bson> pipeline = buildAggregationPipeline(queryBuilder);
                
                LOGGER.log(Level.FINE, "Built aggregation pipeline with {0} stages", pipeline.size());
                
                // Execute pipeline
                List<CrossSchemaResult> results = executePipeline(pipeline, queryBuilder);
                
                // Apply client-side operations if needed
                results = applyClientSideOperations(results, queryBuilder);
                
                return results;
                
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error executing aggregation query", e);
                throw new RuntimeException("Failed to execute MongoDB aggregation query", e);
            }
        }, getExecutorService());
    }
    
    /**
     * Builds the MongoDB aggregation pipeline from the query builder.
     */
    private List<Bson> buildAggregationPipeline(CrossSchemaQueryBuilder queryBuilder) {
        List<Bson> pipeline = new ArrayList<>();
        
        // Start with root collection filters
        List<Bson> rootFilters = buildFilters(queryBuilder.getFilters(), queryBuilder.getRootSchema());
        if (!rootFilters.isEmpty()) {
            pipeline.add(Aggregates.match(Filters.and(rootFilters)));
        }
        
        // Add $lookup stages for joins
        for (JoinOperation join : queryBuilder.getJoins()) {
            pipeline.add(buildLookupStage(join, queryBuilder));
            
            // Add filters for joined data
            List<Bson> joinFilters = buildJoinFilters(join);
            if (!joinFilters.isEmpty()) {
                pipeline.add(Aggregates.match(Filters.and(joinFilters)));
            }
        }
        
        // Add sorting
        Document sortDoc = buildSortDocument(queryBuilder.getSortOrders());
        if (!sortDoc.isEmpty()) {
            pipeline.add(Aggregates.sort(sortDoc));
        }
        
        // Add pagination
        if (queryBuilder.getOffset().isPresent()) {
            pipeline.add(Aggregates.skip(queryBuilder.getOffset().get()));
        }
        if (queryBuilder.getLimit().isPresent()) {
            pipeline.add(Aggregates.limit(queryBuilder.getLimit().get()));
        }
        
        // Add projection to optimize data transfer
        pipeline.add(buildProjection(queryBuilder));
        
        return pipeline;
    }
    
    /**
     * Builds a $lookup stage for a join operation.
     */
    private Bson buildLookupStage(JoinOperation join, CrossSchemaQueryBuilder queryBuilder) {
        CollectionInfo targetInfo = getCollectionInfo(join.getTargetSchema(), null);
        
        // Basic $lookup - join on UUID
        String localField = "_id"; // MongoDB uses _id for UUID
        String foreignField = "_id";
        String as = "joined_" + targetInfo.collectionName;
        
        // Build lookup with pipeline for filters
        List<Bson> lookupPipeline = new ArrayList<>();
        
        // Match on UUID
        lookupPipeline.add(Aggregates.match(
            Filters.expr(
                new Document("$eq", Arrays.asList(
                    "$" + foreignField,
                    "$$localId"
                ))
            )
        ));
        
        // Add join filters
        List<Bson> joinFilters = buildFilters(join.getFilters(), join.getTargetSchema());
        if (!joinFilters.isEmpty()) {
            lookupPipeline.add(Aggregates.match(Filters.and(joinFilters)));
        }
        
        // Create lookup with let variables
        return new Document("$lookup", new Document()
            .append("from", targetInfo.collectionName)
            .append("let", new Document("localId", "$" + localField))
            .append("pipeline", lookupPipeline)
            .append("as", as)
        );
    }
    
    /**
     * Builds filter conditions from QueryFilter objects.
     */
    private List<Bson> buildFilters(List<QueryFilter> filters, PlayerDataSchema<?> schema) {
        return filters.stream()
            .filter(f -> f.getSchema().equals(schema))
            .map(this::buildFilterCondition)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }
    
    /**
     * Builds a single MongoDB filter condition.
     */
    private Bson buildFilterCondition(QueryFilter filter) {
        String field = filter.getFieldName();
        Object value = filter.getValue();
        
        if (filter.getOperator() == null) {
            // Custom predicate - cannot translate to MongoDB
            LOGGER.log(Level.WARNING, "Cannot translate custom predicate to MongoDB filter");
            return null;
        }
        
        switch (filter.getOperator()) {
            case EQUALS:
                return Filters.eq(field, value);
                
            case NOT_EQUALS:
                return Filters.ne(field, value);
                
            case GREATER_THAN:
                return Filters.gt(field, value);
                
            case GREATER_THAN_OR_EQUAL:
                return Filters.gte(field, value);
                
            case LESS_THAN:
                return Filters.lt(field, value);
                
            case LESS_THAN_OR_EQUAL:
                return Filters.lte(field, value);
                
            case LIKE:
            case CONTAINS:
                if (value instanceof String) {
                    // Use regex for LIKE pattern matching
                    String pattern = (String) value;
                    if (filter.getOperator() == QueryFilter.FilterOperator.CONTAINS) {
                        pattern = ".*" + pattern + ".*";
                    }
                    return Filters.regex(field, pattern);
                }
                return null;
                
            case STARTS_WITH:
                if (value instanceof String) {
                    return Filters.regex(field, "^" + value);
                }
                return null;
                
            case ENDS_WITH:
                if (value instanceof String) {
                    return Filters.regex(field, value + "$");
                }
                return null;
                
            case IN:
                if (value instanceof Collection) {
                    return Filters.in(field, (Collection<?>) value);
                }
                return null;
                
            case NOT_IN:
                if (value instanceof Collection) {
                    return Filters.nin(field, (Collection<?>) value);
                }
                return null;
                
            case IS_NULL:
                return Filters.eq(field, null);
                
            case IS_NOT_NULL:
                return Filters.ne(field, null);
                
            case BETWEEN:
                if (value instanceof Object[] && ((Object[]) value).length == 2) {
                    Object[] range = (Object[]) value;
                    return Filters.and(
                        Filters.gte(field, range[0]),
                        Filters.lte(field, range[1])
                    );
                }
                return null;
                
            default:
                LOGGER.log(Level.WARNING, "Unsupported filter operator for MongoDB: {0}", filter.getOperator());
                return null;
        }
    }
    
    /**
     * Builds filters for joined data.
     */
    private List<Bson> buildJoinFilters(JoinOperation join) {
        CollectionInfo info = getCollectionInfo(join.getTargetSchema(), null);
        String prefix = "joined_" + info.collectionName;
        
        return join.getFilters().stream()
            .map(filter -> {
                // Adjust field names to reference joined data
                String field = prefix + "." + filter.getFieldName();
                return buildFilterCondition(new QueryFilter(field, filter.getOperator(), 
                                                            filter.getValue(), filter.getSchema()));
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }
    
    /**
     * Builds a sort document for the aggregation pipeline.
     */
    private Document buildSortDocument(List<SortOrder> sortOrders) {
        Document sortDoc = new Document();
        
        for (SortOrder sortOrder : sortOrders) {
            String field = sortOrder.getFieldName();
            
            // Adjust field name if it's from a joined collection
            if (!sortOrder.getSchema().equals(sortOrders.get(0).getSchema())) {
                CollectionInfo info = getCollectionInfo(sortOrder.getSchema(), null);
                field = "joined_" + info.collectionName + "." + field;
            }
            
            int direction = sortOrder.getDirection() == SortOrder.Direction.ASC ? 1 : -1;
            sortDoc.append(field, direction);
        }
        
        return sortDoc;
    }
    
    /**
     * Builds a projection stage to optimize data transfer.
     */
    private Bson buildProjection(CrossSchemaQueryBuilder queryBuilder) {
        Document projection = new Document();
        
        // Always include _id (UUID)
        projection.append("_id", 1);
        
        // Include all fields from root schema
        projection.append("data", "$$ROOT");
        
        // Include joined data
        for (JoinOperation join : queryBuilder.getJoins()) {
            CollectionInfo info = getCollectionInfo(join.getTargetSchema(), null);
            String joinedField = "joined_" + info.collectionName;
            projection.append(joinedField, 1);
        }
        
        return Aggregates.project(projection);
    }
    
    /**
     * Executes the aggregation pipeline and builds results.
     */
    private List<CrossSchemaResult> executePipeline(List<Bson> pipeline, CrossSchemaQueryBuilder queryBuilder) {
        List<CrossSchemaResult> results = new ArrayList<>();
        
        // Get the root collection
        PlayerDataBackend backend = PlayerDataRegistry.getBackend(queryBuilder.getRootSchema());
        if (!(backend instanceof MongoDataBackend mongoBackend)) {
            throw new IllegalStateException("Expected MongoDataBackend");
        }
        
        CollectionInfo rootInfo = getCollectionInfo(queryBuilder.getRootSchema(), mongoBackend);
        MongoCollection<Document> collection = rootInfo.collection;
        
        // Execute aggregation
        collection.aggregate(pipeline).forEach(doc -> {
            try {
                // Extract UUID
                String uuidStr = doc.getString("_id");
                UUID uuid = UUID.fromString(uuidStr);
                
                CrossSchemaResult result = new CrossSchemaResult(uuid);
                
                // Extract root schema data
                Document rootData = (Document) doc.get("data");
                if (rootData != null) {
                    Object rootObject = deserializeDocument(rootData, queryBuilder.getRootSchema(), uuid);
                    if (rootObject != null) {
                        addSchemaDataUnchecked(result, queryBuilder.getRootSchema(), rootObject);
                    }
                }
                
                // Extract joined data
                for (JoinOperation join : queryBuilder.getJoins()) {
                    CollectionInfo joinInfo = getCollectionInfo(join.getTargetSchema(), null);
                    String joinedField = "joined_" + joinInfo.collectionName;
                    
                    List<Document> joinedDocs = (List<Document>) doc.get(joinedField);
                    if (joinedDocs != null && !joinedDocs.isEmpty()) {
                        // Take first document (should only be one for UUID join)
                        Document joinedDoc = joinedDocs.get(0);
                        Object joinedObject = deserializeDocument(joinedDoc, join.getTargetSchema(), uuid);
                        if (joinedObject != null) {
                            addSchemaDataUnchecked(result, join.getTargetSchema(), joinedObject);
                        }
                    }
                }
                
                results.add(result);
                
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error processing aggregation result", e);
            }
        });
        
        return results;
    }
    
    /**
     * Deserializes a MongoDB document to a schema object.
     */
    private Object deserializeDocument(Document doc, PlayerDataSchema<?> schema, UUID uuid) {
        if (!(schema instanceof JsonSchema<?> jsonSchema)) {
            return null;
        }
        
        try {
            // Remove _id before deserialization
            doc.remove("_id");
            String json = doc.toJson();
            return jsonSchema.deserialize(uuid, json);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to deserialize document for schema " + schema.schemaKey(), e);
            return null;
        }
    }
    
    /**
     * Applies any client-side operations that couldn't be done in the pipeline.
     */
    private List<CrossSchemaResult> applyClientSideOperations(List<CrossSchemaResult> results, 
                                                              CrossSchemaQueryBuilder queryBuilder) {
        // Apply any filters with custom predicates
        for (QueryFilter filter : queryBuilder.getFilters()) {
            if (filter.getOperator() == null) {
                // Custom predicate - apply client-side
                results = results.stream()
                    .filter(result -> {
                        Object data = result.getData(filter.getSchema());
                        return data != null && filter.test(data);
                    })
                    .collect(Collectors.toList());
            }
        }
        
        // Complex sorting if needed
        // MongoDB should handle most sorting, but custom comparators would go here
        
        return results;
    }
    
    /**
     * Gets or loads collection information for a schema.
     */
    private CollectionInfo getCollectionInfo(PlayerDataSchema<?> schema, MongoDataBackend backend) {
        return collectionInfoCache.computeIfAbsent(schema, s -> {
            MongoDataBackend mongoBackend = backend;
            if (mongoBackend == null) {
                PlayerDataBackend b = PlayerDataRegistry.getBackend(s);
                if (!(b instanceof MongoDataBackend)) {
                    throw new IllegalStateException("Expected MongoDataBackend for schema: " + s.schemaKey());
                }
                mongoBackend = (MongoDataBackend) b;
            }
            
            // Extract collection information using reflection
            try {
                Field collectionField = MongoDataBackend.class.getDeclaredField("collection");
                collectionField.setAccessible(true);
                MongoCollection<Document> collection = (MongoCollection<Document>) collectionField.get(mongoBackend);
                
                String collectionName = collection.getNamespace().getCollectionName();
                String databaseName = collection.getNamespace().getDatabaseName();
                
                return new CollectionInfo(collectionName, databaseName, collection);
                
            } catch (Exception e) {
                throw new RuntimeException("Failed to extract collection info", e);
            }
        });
    }
    
    /**
     * Collects all schemas involved in the query.
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
     * Gets the executor service for async operations.
     */
    private ExecutorService getExecutorService() {
        // Access parent's executor service through reflection
        try {
            Field field = SchemaJoinExecutor.class.getDeclaredField("executorService");
            field.setAccessible(true);
            return (ExecutorService) field.get(this);
        } catch (Exception e) {
            throw new RuntimeException("Failed to access executor service", e);
        }
    }
    
    /**
     * Helper method to add schema data without type checking.
     */
    @SuppressWarnings("unchecked")
    private void addSchemaDataUnchecked(CrossSchemaResult result, PlayerDataSchema schema, Object data) {
        result.addSchemaData(schema, data);
    }
    
    /**
     * Container for MongoDB collection information.
     */
    private static class CollectionInfo {
        final String collectionName;
        final String databaseName;
        final MongoCollection<Document> collection;
        
        CollectionInfo(String collectionName, String databaseName, MongoCollection<Document> collection) {
            this.collectionName = collectionName;
            this.databaseName = databaseName;
            this.collection = collection;
        }
    }
}