package sh.harold.fulcrum.api.data.query.backend;

import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.conversions.Bson;
import sh.harold.fulcrum.api.data.backend.sql.SqlDialect;
import sh.harold.fulcrum.api.data.backend.sql.SqlDialectProvider;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.query.*;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Generates backend-specific database queries from cross-schema query builders.
 * 
 * <p>This generator supports:</p>
 * <ul>
 *   <li>SQL query generation with proper JOIN syntax and parameterization</li>
 *   <li>MongoDB aggregation pipeline generation</li>
 *   <li>Query validation and syntax checking</li>
 *   <li>Support for different SQL dialects</li>
 *   <li>Proper escaping and security considerations</li>
 * </ul>
 * 
 * <p>The generator works in conjunction with backend-specific executors to provide
 * optimized query execution across different database systems.</p>
 * 
 * @author Harold
 * @since 1.0
 */
public class DatabaseQueryGenerator {
    
    private static final Logger LOGGER = Logger.getLogger(DatabaseQueryGenerator.class.getName());
    
    /**
     * SQL query generation context.
     */
    public static class SqlQueryContext {
        public final String sql;
        public final List<Object> parameters;
        public final Map<PlayerDataSchema<?>, String> tableAliases;
        
        public SqlQueryContext(String sql, List<Object> parameters, Map<PlayerDataSchema<?>, String> tableAliases) {
            this.sql = sql;
            this.parameters = Collections.unmodifiableList(parameters);
            this.tableAliases = Collections.unmodifiableMap(tableAliases);
        }
    }
    
    /**
     * MongoDB query generation context.
     */
    public static class MongoQueryContext {
        public final List<Bson> pipeline;
        public final String rootCollection;
        public final Map<PlayerDataSchema<?>, String> collectionAliases;
        
        public MongoQueryContext(List<Bson> pipeline, String rootCollection, 
                                Map<PlayerDataSchema<?>, String> collectionAliases) {
            this.pipeline = Collections.unmodifiableList(pipeline);
            this.rootCollection = rootCollection;
            this.collectionAliases = Collections.unmodifiableMap(collectionAliases);
        }
    }
    
    /**
     * Generates an SQL query from a cross-schema query builder.
     * 
     * @param queryBuilder The query builder
     * @param dialect The SQL dialect to use
     * @return SQL query context with parameterized query
     */
    public static SqlQueryContext generateSqlQuery(CrossSchemaQueryBuilder queryBuilder, SqlDialect dialect) {
        LOGGER.log(Level.FINE, "Generating SQL query for root schema: {0}", 
                   queryBuilder.getRootSchema().schemaKey());
        
        StringBuilder sql = new StringBuilder();
        List<Object> parameters = new ArrayList<>();
        Map<PlayerDataSchema<?>, String> tableAliases = generateTableAliases(queryBuilder);
        
        // SELECT clause
        sql.append("SELECT ");
        generateSqlSelectClause(sql, queryBuilder, tableAliases, dialect);
        
        // FROM clause
        sql.append(" FROM ");
        generateSqlFromClause(sql, queryBuilder, tableAliases, dialect);
        
        // JOIN clauses
        generateSqlJoinClauses(sql, queryBuilder, tableAliases, dialect);
        
        // WHERE clause
        List<String> whereConditions = new ArrayList<>();
        generateSqlWhereConditions(whereConditions, parameters, queryBuilder, tableAliases, dialect);
        
        if (!whereConditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", whereConditions));
        }
        
        // GROUP BY clause (if needed for aggregations)
        // Not implemented in this version
        
        // ORDER BY clause
        generateSqlOrderByClause(sql, queryBuilder, tableAliases, dialect);
        
        // LIMIT/OFFSET clause
        generateSqlPaginationClause(sql, queryBuilder, dialect);
        
        String finalSql = sql.toString();
        LOGGER.log(Level.FINE, "Generated SQL: {0}", finalSql);
        
        return new SqlQueryContext(finalSql, parameters, tableAliases);
    }
    
    /**
     * Generates a MongoDB aggregation pipeline from a cross-schema query builder.
     * 
     * @param queryBuilder The query builder
     * @return MongoDB query context with aggregation pipeline
     */
    public static MongoQueryContext generateMongoQuery(CrossSchemaQueryBuilder queryBuilder) {
        LOGGER.log(Level.FINE, "Generating MongoDB query for root schema: {0}", 
                   queryBuilder.getRootSchema().schemaKey());
        
        List<Bson> pipeline = new ArrayList<>();
        Map<PlayerDataSchema<?>, String> collectionAliases = generateCollectionAliases(queryBuilder);
        String rootCollection = queryBuilder.getRootSchema().schemaKey();
        
        // Initial match stage for root filters
        List<Bson> rootFilters = generateMongoFilters(
            queryBuilder.getFilters().stream()
                .filter(f -> f.getSchema().equals(queryBuilder.getRootSchema()))
                .collect(Collectors.toList())
        );
        
        if (!rootFilters.isEmpty()) {
            pipeline.add(Aggregates.match(Filters.and(rootFilters)));
        }
        
        // Lookup stages for joins
        for (JoinOperation join : queryBuilder.getJoins()) {
            pipeline.add(generateMongoLookupStage(join, collectionAliases));
            
            // Post-join filters
            List<Bson> joinFilters = generateMongoJoinFilters(join, collectionAliases);
            if (!joinFilters.isEmpty()) {
                pipeline.add(Aggregates.match(Filters.and(joinFilters)));
            }
        }
        
        // Sort stage
        Document sortDoc = generateMongoSortDocument(queryBuilder.getSortOrders(), collectionAliases);
        if (!sortDoc.isEmpty()) {
            pipeline.add(Aggregates.sort(sortDoc));
        }
        
        // Pagination stages
        queryBuilder.getOffset().ifPresent(offset -> pipeline.add(Aggregates.skip(offset)));
        queryBuilder.getLimit().ifPresent(limit -> pipeline.add(Aggregates.limit(limit)));
        
        // Project stage for optimization
        pipeline.add(generateMongoProjection(queryBuilder, collectionAliases));
        
        LOGGER.log(Level.FINE, "Generated MongoDB pipeline with {0} stages", pipeline.size());
        
        return new MongoQueryContext(pipeline, rootCollection, collectionAliases);
    }
    
    /**
     * Validates a cross-schema query for common issues.
     * 
     * @param queryBuilder The query to validate
     * @return List of validation errors, empty if valid
     */
    public static List<String> validateQuery(CrossSchemaQueryBuilder queryBuilder) {
        List<String> errors = new ArrayList<>();
        
        // Check for null root schema
        if (queryBuilder.getRootSchema() == null) {
            errors.add("Root schema cannot be null");
        }
        
        // Check for circular joins
        Set<PlayerDataSchema<?>> seenSchemas = new HashSet<>();
        seenSchemas.add(queryBuilder.getRootSchema());
        
        for (JoinOperation join : queryBuilder.getJoins()) {
            if (!seenSchemas.add(join.getTargetSchema())) {
                errors.add("Circular join detected for schema: " + join.getTargetSchema().schemaKey());
            }
        }
        
        // Check for invalid filters
        for (QueryFilter filter : queryBuilder.getFilters()) {
            if (filter.getFieldName() == null || filter.getFieldName().isEmpty()) {
                errors.add("Filter field name cannot be null or empty");
            }
        }
        
        // Check for invalid sort orders
        for (SortOrder sortOrder : queryBuilder.getSortOrders()) {
            if (sortOrder.getFieldName() == null || sortOrder.getFieldName().isEmpty()) {
                errors.add("Sort field name cannot be null or empty");
            }
        }
        
        // Check pagination
        queryBuilder.getOffset().ifPresent(offset -> {
            if (offset < 0) {
                errors.add("Offset cannot be negative: " + offset);
            }
        });
        
        queryBuilder.getLimit().ifPresent(limit -> {
            if (limit <= 0) {
                errors.add("Limit must be positive: " + limit);
            }
        });
        
        return errors;
    }
    
    // SQL generation helper methods
    
    private static Map<PlayerDataSchema<?>, String> generateTableAliases(CrossSchemaQueryBuilder queryBuilder) {
        Map<PlayerDataSchema<?>, String> aliases = new LinkedHashMap<>();
        aliases.put(queryBuilder.getRootSchema(), "t0");
        
        int counter = 1;
        for (JoinOperation join : queryBuilder.getJoins()) {
            aliases.put(join.getTargetSchema(), "t" + counter++);
        }
        
        return aliases;
    }
    
    private static void generateSqlSelectClause(StringBuilder sql, CrossSchemaQueryBuilder queryBuilder,
                                               Map<PlayerDataSchema<?>, String> tableAliases, SqlDialect dialect) {
        boolean first = true;
        
        // Always select UUID from root
        sql.append(tableAliases.get(queryBuilder.getRootSchema()))
           .append(".").append(dialect.quoteIdentifier("uuid"))
           .append(" AS uuid");
        first = false;
        
        // Select all columns from all tables with aliases to avoid conflicts
        for (Map.Entry<PlayerDataSchema<?>, String> entry : tableAliases.entrySet()) {
            PlayerDataSchema<?> schema = entry.getKey();
            String alias = entry.getValue();
            
            // In a real implementation, we'd introspect the schema for columns
            // For now, we'll select all columns
            if (!first) sql.append(", ");
            sql.append(alias).append(".*");
        }
    }
    
    private static void generateSqlFromClause(StringBuilder sql, CrossSchemaQueryBuilder queryBuilder,
                                             Map<PlayerDataSchema<?>, String> tableAliases, SqlDialect dialect) {
        String rootTable = queryBuilder.getRootSchema().schemaKey();
        String rootAlias = tableAliases.get(queryBuilder.getRootSchema());
        
        sql.append(dialect.quoteIdentifier(rootTable))
           .append(" ").append(rootAlias);
    }
    
    private static void generateSqlJoinClauses(StringBuilder sql, CrossSchemaQueryBuilder queryBuilder,
                                              Map<PlayerDataSchema<?>, String> tableAliases, SqlDialect dialect) {
        for (JoinOperation join : queryBuilder.getJoins()) {
            String joinType = convertJoinType(join.getJoinType());
            String targetTable = join.getTargetSchema().schemaKey();
            String targetAlias = tableAliases.get(join.getTargetSchema());
            
            // Join on UUID by default
            String sourceAlias = findSourceAlias(join, tableAliases);
            
            sql.append(" ").append(joinType).append(" ")
               .append(dialect.quoteIdentifier(targetTable)).append(" ").append(targetAlias)
               .append(" ON ").append(sourceAlias).append(".").append(dialect.quoteIdentifier("uuid"))
               .append(" = ").append(targetAlias).append(".").append(dialect.quoteIdentifier("uuid"));
        }
    }
    
    private static String convertJoinType(JoinOperation.JoinType joinType) {
        switch (joinType) {
            case INNER:
                return "INNER JOIN";
            case LEFT:
                return "LEFT JOIN";
            case RIGHT:
                return "RIGHT JOIN";
            case FULL:
                return "FULL OUTER JOIN";
            default:
                throw new IllegalArgumentException("Unknown join type: " + joinType);
        }
    }
    
    private static void generateSqlWhereConditions(List<String> conditions, List<Object> parameters,
                                                  CrossSchemaQueryBuilder queryBuilder,
                                                  Map<PlayerDataSchema<?>, String> tableAliases,
                                                  SqlDialect dialect) {
        // Root schema filters
        for (QueryFilter filter : queryBuilder.getFilters()) {
            String condition = generateSqlFilterCondition(filter, 
                tableAliases.get(filter.getSchema()), parameters, dialect);
            if (condition != null) {
                conditions.add(condition);
            }
        }
        
        // Join filters
        for (JoinOperation join : queryBuilder.getJoins()) {
            for (QueryFilter filter : join.getFilters()) {
                String condition = generateSqlFilterCondition(filter,
                    tableAliases.get(join.getTargetSchema()), parameters, dialect);
                if (condition != null) {
                    conditions.add(condition);
                }
            }
        }
    }
    
    private static String generateSqlFilterCondition(QueryFilter filter, String tableAlias,
                                                    List<Object> parameters, SqlDialect dialect) {
        if (filter.getOperator() == null) {
            LOGGER.log(Level.WARNING, "Cannot translate custom predicate to SQL");
            return null;
        }
        
        String column = dialect.quoteIdentifier(filter.getFieldName());
        String fullColumn = tableAlias + "." + column;
        
        switch (filter.getOperator()) {
            case EQUALS:
                parameters.add(filter.getValue());
                return fullColumn + " = ?";
                
            case NOT_EQUALS:
                parameters.add(filter.getValue());
                return fullColumn + " != ?";
                
            case GREATER_THAN:
                parameters.add(filter.getValue());
                return fullColumn + " > ?";
                
            case GREATER_THAN_OR_EQUAL:
                parameters.add(filter.getValue());
                return fullColumn + " >= ?";
                
            case LESS_THAN:
                parameters.add(filter.getValue());
                return fullColumn + " < ?";
                
            case LESS_THAN_OR_EQUAL:
                parameters.add(filter.getValue());
                return fullColumn + " <= ?";
                
            case LIKE:
                parameters.add(filter.getValue());
                return fullColumn + " LIKE ?";
                
            case NOT_LIKE:
                parameters.add(filter.getValue());
                return fullColumn + " NOT LIKE ?";
                
            case IN:
                if (filter.getValue() instanceof Collection<?> collection) {
                    if (collection.isEmpty()) return "1=0";
                    
                    String placeholders = collection.stream()
                        .map(v -> "?")
                        .collect(Collectors.joining(", "));
                    
                    collection.forEach(parameters::add);
                    return fullColumn + " IN (" + placeholders + ")";
                }
                return null;
                
            case NOT_IN:
                if (filter.getValue() instanceof Collection<?> collection) {
                    if (collection.isEmpty()) return "1=1";
                    
                    String placeholders = collection.stream()
                        .map(v -> "?")
                        .collect(Collectors.joining(", "));
                    
                    collection.forEach(parameters::add);
                    return fullColumn + " NOT IN (" + placeholders + ")";
                }
                return null;
                
            case IS_NULL:
                return fullColumn + " IS NULL";
                
            case IS_NOT_NULL:
                return fullColumn + " IS NOT NULL";
                
            case BETWEEN:
                if (filter.getValue() instanceof Object[] range && range.length == 2) {
                    parameters.add(range[0]);
                    parameters.add(range[1]);
                    return fullColumn + " BETWEEN ? AND ?";
                }
                return null;
                
            case STARTS_WITH:
                parameters.add(filter.getValue() + "%");
                return fullColumn + " LIKE ?";
                
            case ENDS_WITH:
                parameters.add("%" + filter.getValue());
                return fullColumn + " LIKE ?";
                
            case CONTAINS:
                parameters.add("%" + filter.getValue() + "%");
                return fullColumn + " LIKE ?";
                
            default:
                LOGGER.log(Level.WARNING, "Unsupported SQL filter operator: {0}", filter.getOperator());
                return null;
        }
    }
    
    private static void generateSqlOrderByClause(StringBuilder sql, CrossSchemaQueryBuilder queryBuilder,
                                                Map<PlayerDataSchema<?>, String> tableAliases,
                                                SqlDialect dialect) {
        if (queryBuilder.getSortOrders().isEmpty()) {
            return;
        }
        
        sql.append(" ORDER BY ");
        boolean first = true;
        
        for (SortOrder sortOrder : queryBuilder.getSortOrders()) {
            if (!first) sql.append(", ");
            first = false;
            
            String alias = tableAliases.get(sortOrder.getSchema());
            sql.append(alias).append(".").append(dialect.quoteIdentifier(sortOrder.getFieldName()));
            sql.append(" ").append(sortOrder.getDirection() == SortOrder.Direction.ASC ? "ASC" : "DESC");
            
            // Add null handling if supported by dialect
            if (sortOrder.getNullHandling() == SortOrder.NullHandling.NULLS_FIRST) {
                sql.append(" NULLS FIRST");
            } else {
                sql.append(" NULLS LAST");
            }
        }
    }
    
    private static void generateSqlPaginationClause(StringBuilder sql, CrossSchemaQueryBuilder queryBuilder,
                                                   SqlDialect dialect) {
        Optional<Integer> limit = queryBuilder.getLimit();
        Optional<Integer> offset = queryBuilder.getOffset();
        
        if (limit.isPresent() || offset.isPresent()) {
            // Most modern SQL databases support LIMIT/OFFSET
            sql.append(" LIMIT ").append(limit.orElse(Integer.MAX_VALUE));
            if (offset.isPresent() && offset.get() > 0) {
                sql.append(" OFFSET ").append(offset.get());
            }
        }
    }
    
    // MongoDB generation helper methods
    
    private static Map<PlayerDataSchema<?>, String> generateCollectionAliases(CrossSchemaQueryBuilder queryBuilder) {
        Map<PlayerDataSchema<?>, String> aliases = new LinkedHashMap<>();
        
        for (JoinOperation join : queryBuilder.getJoins()) {
            aliases.put(join.getTargetSchema(), "joined_" + join.getTargetSchema().schemaKey());
        }
        
        return aliases;
    }
    
    private static List<Bson> generateMongoFilters(List<QueryFilter> filters) {
        List<Bson> bsonFilters = new ArrayList<>();
        
        for (QueryFilter filter : filters) {
            Bson bsonFilter = generateMongoFilterCondition(filter);
            if (bsonFilter != null) {
                bsonFilters.add(bsonFilter);
            }
        }
        
        return bsonFilters;
    }
    
    private static Bson generateMongoFilterCondition(QueryFilter filter) {
        if (filter.getOperator() == null) {
            LOGGER.log(Level.WARNING, "Cannot translate custom predicate to MongoDB");
            return null;
        }
        
        String field = filter.getFieldName();
        Object value = filter.getValue();
        
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
                return Filters.regex(field, ".*" + value + ".*");
            case STARTS_WITH:
                return Filters.regex(field, "^" + value);
            case ENDS_WITH:
                return Filters.regex(field, value + "$");
            case NOT_LIKE:
                return Filters.not(Filters.regex(field, ".*" + value + ".*"));
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
                if (value instanceof Object[] range && range.length == 2) {
                    return Filters.and(
                        Filters.gte(field, range[0]),
                        Filters.lte(field, range[1])
                    );
                }
                return null;
            default:
                LOGGER.log(Level.WARNING, "Unsupported MongoDB filter operator: {0}", filter.getOperator());
                return null;
        }
    }
    
    private static Bson generateMongoLookupStage(JoinOperation join, Map<PlayerDataSchema<?>, String> aliases) {
        String targetCollection = join.getTargetSchema().schemaKey();
        String as = aliases.get(join.getTargetSchema());
        
        // Build lookup with pipeline for join filters
        List<Bson> lookupPipeline = new ArrayList<>();
        
        // Match on _id (UUID)
        lookupPipeline.add(Aggregates.match(
            Filters.expr(
                new Document("$eq", Arrays.asList("$_id", "$$localId"))
            )
        ));
        
        // Add join-specific filters
        List<Bson> joinFilters = generateMongoFilters(join.getFilters());
        if (!joinFilters.isEmpty()) {
            lookupPipeline.add(Aggregates.match(Filters.and(joinFilters)));
        }
        
        return new Document("$lookup", new Document()
            .append("from", targetCollection)
            .append("let", new Document("localId", "$_id"))
            .append("pipeline", lookupPipeline)
            .append("as", as)
        );
    }
    
    private static List<Bson> generateMongoJoinFilters(JoinOperation join, Map<PlayerDataSchema<?>, String> aliases) {
        String prefix = aliases.get(join.getTargetSchema());
        List<Bson> filters = new ArrayList<>();
        
        // For outer joins, we need to handle missing data
        if (join.getJoinType() == JoinOperation.JoinType.INNER) {
            // Inner join requires matching documents
            filters.add(Filters.expr(
                new Document("$gt", Arrays.asList(
                    new Document("$size", "$" + prefix), 0
                ))
            ));
        }
        
        return filters;
    }
    
    private static Document generateMongoSortDocument(List<SortOrder> sortOrders,
                                                     Map<PlayerDataSchema<?>, String> aliases) {
        Document sortDoc = new Document();
        
        for (SortOrder sortOrder : sortOrders) {
            String field = sortOrder.getFieldName();
            
            // Adjust field name for joined collections
            if (!sortOrder.getSchema().equals(sortOrders.get(0).getSchema())) {
                String alias = aliases.get(sortOrder.getSchema());
                if (alias != null) {
                    field = alias + ".0." + field; // MongoDB array notation
                }
            }
            
            int direction = sortOrder.getDirection() == SortOrder.Direction.ASC ? 1 : -1;
            sortDoc.append(field, direction);
        }
        
        return sortDoc;
    }
    
    private static Bson generateMongoProjection(CrossSchemaQueryBuilder queryBuilder,
                                               Map<PlayerDataSchema<?>, String> aliases) {
        Document projection = new Document();
        
        // Always include _id
        projection.append("_id", 1);
        
        // Include all root fields
        projection.append("data", "$$ROOT");
        
        // Include joined collections
        for (Map.Entry<PlayerDataSchema<?>, String> entry : aliases.entrySet()) {
            projection.append(entry.getValue(), 1);
        }
        
        return Aggregates.project(projection);
    }
    
    private static String findSourceAlias(JoinOperation join, Map<PlayerDataSchema<?>, String> tableAliases) {
        // For simplicity, assume joins are sequential from root
        // In a more complex implementation, we'd track join dependencies
        List<String> aliases = new ArrayList<>(tableAliases.values());
        int targetIndex = aliases.indexOf(tableAliases.get(join.getTargetSchema()));
        
        if (targetIndex > 0) {
            return aliases.get(targetIndex - 1);
        }
        
        return aliases.get(0); // Root alias
    }
}