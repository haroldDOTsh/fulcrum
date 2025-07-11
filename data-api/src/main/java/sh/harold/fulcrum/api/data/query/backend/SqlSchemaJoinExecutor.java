package sh.harold.fulcrum.api.data.query.backend;

import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.backend.sql.SqlDataBackend;
import sh.harold.fulcrum.api.data.backend.sql.SqlDialect;
import sh.harold.fulcrum.api.data.backend.sql.SqlDialectProvider;
import sh.harold.fulcrum.api.data.backend.core.AutoTableSchema;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.impl.TableSchema;
import sh.harold.fulcrum.api.data.query.*;
import sh.harold.fulcrum.api.data.registry.PlayerDataRegistry;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * SQL-specific implementation of cross-schema query execution with optimizations
 * for native SQL JOINs when schemas are in the same database.
 * 
 * <p>This executor provides the following optimizations:</p>
 * <ul>
 *   <li>Native SQL JOINs when schemas are in the same database connection</li>
 *   <li>Efficient filtering using WHERE clauses pushed to the database</li>
 *   <li>Prepared statements for performance and security</li>
 *   <li>Batch loading for large result sets</li>
 *   <li>Connection pooling awareness</li>
 * </ul>
 * 
 * <p>When schemas are in different databases, falls back to application-level
 * UUID intersection using the parent SchemaJoinExecutor logic.</p>
 * 
 * @author Harold
 * @since 1.0
 */
public class SqlSchemaJoinExecutor extends SchemaJoinExecutor {
    
    private static final Logger LOGGER = Logger.getLogger(SqlSchemaJoinExecutor.class.getName());
    
    /**
     * Cache for schema table metadata.
     */
    private final Map<PlayerDataSchema<?>, TableMetadata> tableMetadataCache = new ConcurrentHashMap<>();
    
    /**
     * SQL dialect for query generation.
     */
    private final SqlDialect dialect;
    
    /**
     * Creates a new SqlSchemaJoinExecutor with default components.
     */
    public SqlSchemaJoinExecutor() {
        super();
        this.dialect = SqlDialectProvider.get();
    }
    
    /**
     * Creates a new SqlSchemaJoinExecutor with specified executor service.
     * 
     * @param executorService The executor service for async operations
     */
    public SqlSchemaJoinExecutor(ExecutorService executorService) {
        super(executorService);
        this.dialect = SqlDialectProvider.get();
    }
    
    /**
     * Executes a cross-schema query with SQL-specific optimizations.
     * 
     * @param queryBuilder The query builder containing the query specification
     * @return A CompletableFuture containing the query results
     */
    @Override
    public CompletableFuture<List<CrossSchemaResult>> execute(CrossSchemaQueryBuilder queryBuilder) {
        LOGGER.log(Level.FINE, "Executing SQL-optimized cross-schema query for root schema: {0}", 
                   queryBuilder.getRootSchema().schemaKey());
        
        // Check if all schemas use the same SQL backend connection
        if (canUseNativeSqlJoin(queryBuilder)) {
            return executeNativeSqlQuery(queryBuilder);
        } else {
            // Fall back to application-level join
            LOGGER.log(Level.FINE, "Schemas use different connections, falling back to application-level join");
            return super.execute(queryBuilder);
        }
    }
    
    /**
     * Checks if all schemas in the query use the same SQL connection.
     */
    private boolean canUseNativeSqlJoin(CrossSchemaQueryBuilder queryBuilder) {
        Set<PlayerDataSchema<?>> schemas = collectAllSchemas(queryBuilder);
        
        SqlDataBackend firstBackend = null;
        Connection firstConnection = null;
        
        for (PlayerDataSchema<?> schema : schemas) {
            PlayerDataBackend backend = PlayerDataRegistry.getBackend(schema);
            
            if (!(backend instanceof SqlDataBackend)) {
                return false; // Not all backends are SQL
            }
            
            SqlDataBackend sqlBackend = (SqlDataBackend) backend;
            
            if (firstBackend == null) {
                firstBackend = sqlBackend;
                firstConnection = sqlBackend.getConnection();
            } else {
                // Check if connections are the same
                if (sqlBackend.getConnection() != firstConnection) {
                    return false; // Different connections
                }
            }
        }
        
        return true;
    }
    
    /**
     * Executes the query using native SQL JOINs.
     */
    private CompletableFuture<List<CrossSchemaResult>> executeNativeSqlQuery(CrossSchemaQueryBuilder queryBuilder) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Generate optimized SQL query
                SqlQuery sqlQuery = generateSqlQuery(queryBuilder);
                
                LOGGER.log(Level.FINE, "Generated SQL query: {0}", sqlQuery.sql);
                
                // Execute query
                List<CrossSchemaResult> results = executeSqlQuery(sqlQuery, queryBuilder);
                
                // Apply client-side sorting if needed (complex sorts)
                results = applyComplexSorting(results, queryBuilder.getSortOrders());
                
                // Apply pagination if not already done in SQL
                if (!sqlQuery.hasPagination) {
                    results = applyPagination(results, queryBuilder.getLimit(), queryBuilder.getOffset());
                }
                
                return results;
                
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error executing native SQL query", e);
                throw new RuntimeException("Failed to execute SQL query", e);
            }
        }, getExecutorService());
    }
    
    /**
     * Generates an optimized SQL query from the query builder.
     */
    private SqlQuery generateSqlQuery(CrossSchemaQueryBuilder queryBuilder) {
        StringBuilder sql = new StringBuilder();
        List<Object> parameters = new ArrayList<>();
        Map<PlayerDataSchema<?>, String> tableAliases = new HashMap<>();
        
        // Generate table aliases
        tableAliases.put(queryBuilder.getRootSchema(), "t0");
        int aliasCounter = 1;
        for (JoinOperation join : queryBuilder.getJoins()) {
            tableAliases.put(join.getTargetSchema(), "t" + aliasCounter++);
        }
        
        // SELECT clause
        sql.append("SELECT ");
        generateSelectClause(sql, queryBuilder, tableAliases);
        
        // FROM clause
        sql.append(" FROM ");
        String rootTable = getTableName(queryBuilder.getRootSchema());
        sql.append(dialect.quoteIdentifier(rootTable))
           .append(" ").append(tableAliases.get(queryBuilder.getRootSchema()));
        
        // JOIN clauses
        for (JoinOperation join : queryBuilder.getJoins()) {
            generateJoinClause(sql, join, tableAliases);
        }
        
        // WHERE clause
        List<String> whereConditions = new ArrayList<>();
        generateWhereConditions(whereConditions, parameters, queryBuilder, tableAliases);
        
        if (!whereConditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", whereConditions));
        }
        
        // ORDER BY clause
        boolean hasOrderBy = generateOrderByClause(sql, queryBuilder.getSortOrders(), tableAliases);
        
        // LIMIT/OFFSET clause
        boolean hasPagination = false;
        if (queryBuilder.getLimit().isPresent() || queryBuilder.getOffset().isPresent()) {
            hasPagination = generatePaginationClause(sql, queryBuilder.getLimit(), queryBuilder.getOffset());
        }
        
        return new SqlQuery(sql.toString(), parameters, tableAliases, hasPagination);
    }
    
    /**
     * Generates the SELECT clause with all needed columns.
     */
    private void generateSelectClause(StringBuilder sql, CrossSchemaQueryBuilder queryBuilder,
                                      Map<PlayerDataSchema<?>, String> tableAliases) {
        boolean first = true;
        
        // Select UUID column from root table
        if (!first) sql.append(", ");
        sql.append(tableAliases.get(queryBuilder.getRootSchema()))
           .append(".").append(dialect.quoteIdentifier(getPrimaryKeyColumn(queryBuilder.getRootSchema())))
           .append(" AS uuid");
        first = false;
        
        // Select all columns from all tables
        for (Map.Entry<PlayerDataSchema<?>, String> entry : tableAliases.entrySet()) {
            PlayerDataSchema<?> schema = entry.getKey();
            String alias = entry.getValue();
            
            for (String column : getTableColumns(schema)) {
                if (!first) sql.append(", ");
                sql.append(alias).append(".").append(dialect.quoteIdentifier(column))
                   .append(" AS ").append(dialect.quoteIdentifier(alias + "_" + column));
                first = false;
            }
        }
    }
    
    /**
     * Generates a JOIN clause for a join operation.
     */
    private void generateJoinClause(StringBuilder sql, JoinOperation join,
                                    Map<PlayerDataSchema<?>, String> tableAliases) {
        String joinType = switch (join.getJoinType()) {
            case INNER -> "INNER JOIN";
            case LEFT -> "LEFT JOIN";
            case RIGHT -> "RIGHT JOIN";
            case FULL -> "FULL OUTER JOIN";
        };
        
        String targetTable = getTableName(join.getTargetSchema());
        String targetAlias = tableAliases.get(join.getTargetSchema());
        String targetPk = getPrimaryKeyColumn(join.getTargetSchema());
        
        // Find the source alias (could be root or previous join)
        String sourceAlias = findSourceAlias(join, tableAliases);
        String sourcePk = getPrimaryKeyColumn(findSourceSchema(join, tableAliases));
        
        sql.append(" ").append(joinType).append(" ")
           .append(dialect.quoteIdentifier(targetTable)).append(" ").append(targetAlias)
           .append(" ON ").append(sourceAlias).append(".").append(dialect.quoteIdentifier(sourcePk))
           .append(" = ").append(targetAlias).append(".").append(dialect.quoteIdentifier(targetPk));
    }
    
    /**
     * Generates WHERE conditions from filters.
     */
    private void generateWhereConditions(List<String> conditions, List<Object> parameters,
                                         CrossSchemaQueryBuilder queryBuilder,
                                         Map<PlayerDataSchema<?>, String> tableAliases) {
        // Root schema filters
        for (QueryFilter filter : queryBuilder.getFilters()) {
            if (filter.getSchema().equals(queryBuilder.getRootSchema())) {
                String condition = generateFilterCondition(filter, tableAliases.get(filter.getSchema()), parameters);
                if (condition != null) {
                    conditions.add(condition);
                }
            }
        }
        
        // Join filters
        for (JoinOperation join : queryBuilder.getJoins()) {
            for (QueryFilter filter : join.getFilters()) {
                String condition = generateFilterCondition(filter, tableAliases.get(join.getTargetSchema()), parameters);
                if (condition != null) {
                    conditions.add(condition);
                }
            }
        }
    }
    
    /**
     * Generates a SQL condition from a QueryFilter.
     */
    private String generateFilterCondition(QueryFilter filter, String tableAlias, List<Object> parameters) {
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
                
            case IN:
                if (filter.getValue() instanceof Collection<?> collection) {
                    if (collection.isEmpty()) return "1=0"; // Always false
                    
                    String placeholders = collection.stream()
                        .map(v -> "?")
                        .collect(Collectors.joining(", "));
                    
                    collection.forEach(parameters::add);
                    return fullColumn + " IN (" + placeholders + ")";
                }
                return null;
                
            case IS_NULL:
                return fullColumn + " IS NULL";
                
            case IS_NOT_NULL:
                return fullColumn + " IS NOT NULL";
                
            default:
                LOGGER.log(Level.WARNING, "Unsupported filter operator for SQL: {0}", filter.getOperator());
                return null;
        }
    }
    
    /**
     * Generates ORDER BY clause.
     */
    private boolean generateOrderByClause(StringBuilder sql, List<SortOrder> sortOrders,
                                          Map<PlayerDataSchema<?>, String> tableAliases) {
        if (sortOrders.isEmpty()) {
            return false;
        }
        
        List<String> orderClauses = new ArrayList<>();
        
        for (SortOrder sortOrder : sortOrders) {
            String alias = tableAliases.get(sortOrder.getSchema());
            if (alias != null) {
                String column = dialect.quoteIdentifier(sortOrder.getFieldName());
                String direction = sortOrder.getDirection() == SortOrder.Direction.ASC ? "ASC" : "DESC";
                String nullHandling = sortOrder.getNullHandling() == SortOrder.NullHandling.NULLS_FIRST
                    ? "NULLS FIRST" : "NULLS LAST";
                
                orderClauses.add(alias + "." + column + " " + direction + " " + nullHandling);
            }
        }
        
        if (!orderClauses.isEmpty()) {
            sql.append(" ORDER BY ").append(String.join(", ", orderClauses));
            return true;
        }
        
        return false;
    }
    
    /**
     * Generates LIMIT/OFFSET clause based on dialect.
     */
    private boolean generatePaginationClause(StringBuilder sql, Optional<Integer> limit, Optional<Integer> offset) {
        if (limit.isPresent() || offset.isPresent()) {
            int limitValue = limit.orElse(Integer.MAX_VALUE);
            int offsetValue = offset.orElse(0);
            
            // Most SQL dialects support LIMIT/OFFSET
            sql.append(" LIMIT ").append(limitValue);
            if (offsetValue > 0) {
                sql.append(" OFFSET ").append(offsetValue);
            }
            
            return true;
        }
        
        return false;
    }
    
    /**
     * Executes the generated SQL query and builds results.
     */
    private List<CrossSchemaResult> executeSqlQuery(SqlQuery sqlQuery, CrossSchemaQueryBuilder queryBuilder) 
            throws SQLException {
        List<CrossSchemaResult> results = new ArrayList<>();
        
        // Get connection from any backend (they're all the same)
        PlayerDataBackend backend = PlayerDataRegistry.getBackend(queryBuilder.getRootSchema());
        if (!(backend instanceof SqlDataBackend sqlBackend)) {
            throw new IllegalStateException("Expected SqlDataBackend");
        }
        
        Connection connection = sqlBackend.getConnection();
        
        try (PreparedStatement ps = connection.prepareStatement(sqlQuery.sql)) {
            // Set parameters
            for (int i = 0; i < sqlQuery.parameters.size(); i++) {
                ps.setObject(i + 1, sqlQuery.parameters.get(i));
            }
            
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // Extract UUID
                    String uuidStr = rs.getString("uuid");
                    UUID uuid = UUID.fromString(uuidStr);
                    
                    CrossSchemaResult result = new CrossSchemaResult(uuid);
                    
                    // Extract data for each schema
                    for (Map.Entry<PlayerDataSchema<?>, String> entry : sqlQuery.tableAliases.entrySet()) {
                        PlayerDataSchema<?> schema = entry.getKey();
                        String alias = entry.getValue();
                        
                        Object data = extractSchemaData(rs, schema, alias);
                        if (data != null) {
                            addSchemaDataUnchecked(result, schema, data);
                        }
                    }
                    
                    results.add(result);
                }
            }
        }
        
        return results;
    }
    
    /**
     * Extracts data for a specific schema from the result set.
     */
    @SuppressWarnings("unchecked")
    private Object extractSchemaData(ResultSet rs, PlayerDataSchema<?> schema, String tableAlias) {
        try {
            if (!(schema instanceof TableSchema)) {
                return null;
            }
            
            // Use reflection to create instance and populate fields
            Class<?> type = schema.type();
            Object instance = type.getDeclaredConstructor().newInstance();
            
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) ||
                    java.lang.reflect.Modifier.isTransient(field.getModifiers())) {
                    continue;
                }
                
                field.setAccessible(true);
                String columnName = field.getName(); // Simplified - should use @Column annotation
                String aliasedColumn = tableAlias + "_" + columnName;
                
                try {
                    Object value = getValueFromResultSet(rs, aliasedColumn, field.getType());
                    field.set(instance, value);
                } catch (SQLException e) {
                    // Column might not exist in result set
                    LOGGER.log(Level.FINEST, "Column not found in result set: {0}", aliasedColumn);
                }
            }
            
            return instance;
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to extract schema data for " + schema.schemaKey(), e);
            return null;
        }
    }
    
    /**
     * Gets a value from ResultSet based on type.
     */
    private Object getValueFromResultSet(ResultSet rs, String column, Class<?> type) throws SQLException {
        if (type == UUID.class) {
            String str = rs.getString(column);
            return str == null ? null : UUID.fromString(str);
        } else if (type == String.class) {
            return rs.getString(column);
        } else if (type == int.class || type == Integer.class) {
            return rs.getInt(column);
        } else if (type == long.class || type == Long.class) {
            return rs.getLong(column);
        } else if (type == boolean.class || type == Boolean.class) {
            return rs.getBoolean(column);
        } else if (type.isEnum()) {
            String str = rs.getString(column);
            if (str == null) return null;
            return Enum.valueOf((Class<? extends Enum>) type, str);
        }
        
        throw new IllegalArgumentException("Unsupported type: " + type.getName());
    }
    
    /**
     * Gets table name for a schema.
     */
    private String getTableName(PlayerDataSchema<?> schema) {
        return getTableMetadata(schema).tableName;
    }
    
    /**
     * Gets primary key column for a schema.
     */
    private String getPrimaryKeyColumn(PlayerDataSchema<?> schema) {
        return getTableMetadata(schema).primaryKeyColumn;
    }
    
    /**
     * Gets all columns for a schema.
     */
    private List<String> getTableColumns(PlayerDataSchema<?> schema) {
        return getTableMetadata(schema).columns;
    }
    
    /**
     * Gets or loads table metadata for a schema.
     */
    private TableMetadata getTableMetadata(PlayerDataSchema<?> schema) {
        return tableMetadataCache.computeIfAbsent(schema, s -> {
            if (!(s instanceof TableSchema)) {
                throw new IllegalArgumentException("Not a TableSchema: " + s.type());
            }
            
            String tableName = s.schemaKey();
            List<String> columns = new ArrayList<>();
            String primaryKey = null;
            
            // Use reflection to find columns
            Class<?> type = s.type();
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) ||
                    java.lang.reflect.Modifier.isTransient(field.getModifiers())) {
                    continue;
                }
                
                String columnName = field.getName(); // Simplified
                columns.add(columnName);
                
                // Check for primary key
                var colAnn = field.getAnnotation(sh.harold.fulcrum.api.data.annotation.Column.class);
                if (colAnn != null && colAnn.primary()) {
                    primaryKey = columnName;
                }
            }
            
            // Default primary key detection
            if (primaryKey == null) {
                if (columns.contains("uuid")) {
                    primaryKey = "uuid";
                } else if (columns.contains("id")) {
                    primaryKey = "id";
                }
            }
            
            return new TableMetadata(tableName, primaryKey, columns);
        });
    }
    
    /**
     * Finds the source alias for a join operation.
     */
    private String findSourceAlias(JoinOperation join, Map<PlayerDataSchema<?>, String> tableAliases) {
        // For now, assume joins are sequential from root
        // In a more complex implementation, we'd track join dependencies
        return tableAliases.values().stream()
            .filter(alias -> !alias.equals(tableAliases.get(join.getTargetSchema())))
            .reduce((first, second) -> second)
            .orElse("t0");
    }
    
    /**
     * Finds the source schema for a join operation.
     */
    private PlayerDataSchema<?> findSourceSchema(JoinOperation join, Map<PlayerDataSchema<?>, String> tableAliases) {
        String sourceAlias = findSourceAlias(join, tableAliases);
        return tableAliases.entrySet().stream()
            .filter(e -> e.getValue().equals(sourceAlias))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
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
     * Applies complex sorting that couldn't be done in SQL.
     */
    private List<CrossSchemaResult> applyComplexSorting(List<CrossSchemaResult> results, 
                                                        List<SortOrder> sortOrders) {
        // For now, SQL handles all sorting
        // This method is for future complex sort scenarios
        return results;
    }
    
    /**
     * Applies pagination to results.
     */
    private List<CrossSchemaResult> applyPagination(List<CrossSchemaResult> results,
                                                    Optional<Integer> limit,
                                                    Optional<Integer> offset) {
        int startIndex = offset.orElse(0);
        int endIndex = results.size();
        
        if (limit.isPresent()) {
            endIndex = Math.min(startIndex + limit.get(), results.size());
        }
        
        if (startIndex >= results.size()) {
            return Collections.emptyList();
        }
        
        return results.subList(startIndex, endIndex);
    }
    
    /**
     * Gets the executor service for async operations.
     */
    private ExecutorService getExecutorService() {
        // Access parent's executor service through reflection or make it protected
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
     * Container for generated SQL query and metadata.
     */
    private static class SqlQuery {
        final String sql;
        final List<Object> parameters;
        final Map<PlayerDataSchema<?>, String> tableAliases;
        final boolean hasPagination;
        
        SqlQuery(String sql, List<Object> parameters, Map<PlayerDataSchema<?>, String> tableAliases, 
                 boolean hasPagination) {
            this.sql = sql;
            this.parameters = parameters;
            this.tableAliases = tableAliases;
            this.hasPagination = hasPagination;
        }
    }
    
    /**
     * Container for table metadata.
     */
    private static class TableMetadata {
        final String tableName;
        final String primaryKeyColumn;
        final List<String> columns;
        
        TableMetadata(String tableName, String primaryKeyColumn, List<String> columns) {
            this.tableName = tableName;
            this.primaryKeyColumn = primaryKeyColumn;
            this.columns = columns;
        }
    }
    
    /**
     * Executes a streaming SQL query with cursor-based result handling.
     *
     * @param sql The SQL query to execute
     * @param params Query parameters
     * @param resultConsumer Consumer to process each row
     * @param fetchSize The fetch size for streaming
     * @throws SQLException if query execution fails
     */
    public void executeStreamingQuery(String sql, List<Object> params,
                                    Consumer<Map<String, Object>> resultConsumer,
                                    int fetchSize) throws SQLException {
        // Get connection from any backend
        PlayerDataBackend backend = PlayerDataRegistry.getBackend(tableMetadataCache.keySet().iterator().next());
        if (!(backend instanceof SqlDataBackend sqlBackend)) {
            throw new IllegalStateException("Expected SqlDataBackend");
        }
        
        try (Connection conn = sqlBackend.getConnection()) {
            // Enable streaming mode
            conn.setAutoCommit(false);
            
            try (PreparedStatement stmt = conn.prepareStatement(sql,
                    ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY)) {
                
                // Set fetch size for streaming
                stmt.setFetchSize(fetchSize);
                
                // Set parameters
                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }
                
                // Stream results
                try (ResultSet rs = stmt.executeQuery()) {
                    ResultSetMetaData metaData = rs.getMetaData();
                    int columnCount = metaData.getColumnCount();
                    
                    while (rs.next()) {
                        Map<String, Object> row = new HashMap<>();
                        for (int i = 1; i <= columnCount; i++) {
                            row.put(metaData.getColumnName(i), rs.getObject(i));
                        }
                        resultConsumer.accept(row);
                    }
                }
            } finally {
                // Reset auto-commit
                conn.setAutoCommit(true);
            }
        }
    }
    
    /**
     * Executes a streaming cross-schema query that processes results as they arrive.
     * 
     * @param queryBuilder The query builder
     * @param resultConsumer Consumer to process each result
     * @param fetchSize The fetch size for streaming
     * @return A CompletableFuture that completes when streaming is done
     */
    public CompletableFuture<Void> executeStreaming(CrossSchemaQueryBuilder queryBuilder,
                                                   Consumer<CrossSchemaResult> resultConsumer,
                                                   int fetchSize) {
        return CompletableFuture.runAsync(() -> {
            try {
                if (!canUseNativeSqlJoin(queryBuilder)) {
                    throw new IllegalStateException("Cannot use native SQL streaming for mixed backends");
                }
                
                // Generate SQL query
                SqlQuery sqlQuery = generateSqlQuery(queryBuilder);
                
                // Stream results
                executeStreamingQuery(sqlQuery.sql, sqlQuery.parameters, row -> {
                    // Convert row to CrossSchemaResult
                    String uuidStr = (String) row.get("uuid");
                    UUID uuid = UUID.fromString(uuidStr);
                    
                    CrossSchemaResult result = new CrossSchemaResult(uuid);
                    
                    // Extract data for each schema
                    for (Map.Entry<PlayerDataSchema<?>, String> entry : sqlQuery.tableAliases.entrySet()) {
                        PlayerDataSchema<?> schema = entry.getKey();
                        String alias = entry.getValue();
                        
                        Object data = extractSchemaDataFromRow(row, schema, alias);
                        if (data != null) {
                            addSchemaDataUnchecked(result, schema, data);
                        }
                    }
                    
                    resultConsumer.accept(result);
                }, fetchSize);
                
            } catch (Exception e) {
                throw new RuntimeException("Failed to execute streaming query", e);
            }
        }, getExecutorService());
    }
    
    /**
     * Gets the total count for a query (for pagination).
     * 
     * @param queryBuilder The query builder
     * @return The total count
     */
    public CompletableFuture<Long> getCount(CrossSchemaQueryBuilder queryBuilder) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Generate count query
                String countSql = generateCountQuery(queryBuilder);
                List<Object> params = new ArrayList<>();
                
                // Add filter parameters from all schemas
                collectAllFilterParameters(queryBuilder, params);
                
                // Get connection
                PlayerDataBackend backend = PlayerDataRegistry.getBackend(queryBuilder.getRootSchema());
                if (!(backend instanceof SqlDataBackend sqlBackend)) {
                    throw new IllegalStateException("Expected SqlDataBackend");
                }
                
                try (Connection conn = sqlBackend.getConnection();
                     PreparedStatement ps = conn.prepareStatement(countSql)) {
                    
                    // Set parameters
                    for (int i = 0; i < params.size(); i++) {
                        ps.setObject(i + 1, params.get(i));
                    }
                    
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return rs.getLong(1);
                        }
                        return 0L;
                    }
                }
                
            } catch (Exception e) {
                throw new RuntimeException("Failed to get count", e);
            }
        }, getExecutorService());
    }
    
    /**
     * Generates a COUNT query from the query builder.
     */
    private String generateCountQuery(CrossSchemaQueryBuilder queryBuilder) {
        SqlQuery sqlQuery = generateSqlQuery(queryBuilder);
        
        // Extract parts of the query
        String fromClause = extractFromClause(sqlQuery.sql);
        String whereClause = extractWhereClause(sqlQuery.sql);
        
        StringBuilder countQuery = new StringBuilder("SELECT COUNT(DISTINCT ");
        countQuery.append(sqlQuery.tableAliases.get(queryBuilder.getRootSchema()))
                 .append(".").append(dialect.quoteIdentifier(getPrimaryKeyColumn(queryBuilder.getRootSchema())))
                 .append(") FROM ").append(fromClause);
        
        if (!whereClause.isEmpty()) {
            countQuery.append(" WHERE ").append(whereClause);
        }
        
        return countQuery.toString();
    }
    
    /**
     * Extracts the FROM clause from a SQL query.
     */
    private String extractFromClause(String sql) {
        int fromIndex = sql.indexOf(" FROM ");
        if (fromIndex == -1) return "";
        
        int whereIndex = sql.indexOf(" WHERE ");
        int orderByIndex = sql.indexOf(" ORDER BY ");
        int limitIndex = sql.indexOf(" LIMIT ");
        
        int endIndex = sql.length();
        if (whereIndex != -1) endIndex = Math.min(endIndex, whereIndex);
        if (orderByIndex != -1) endIndex = Math.min(endIndex, orderByIndex);
        if (limitIndex != -1) endIndex = Math.min(endIndex, limitIndex);
        
        return sql.substring(fromIndex + 6, endIndex).trim();
    }
    
    /**
     * Extracts the WHERE clause from a SQL query.
     */
    private String extractWhereClause(String sql) {
        int whereIndex = sql.indexOf(" WHERE ");
        if (whereIndex == -1) return "";
        
        int orderByIndex = sql.indexOf(" ORDER BY ");
        int limitIndex = sql.indexOf(" LIMIT ");
        
        int endIndex = sql.length();
        if (orderByIndex != -1) endIndex = Math.min(endIndex, orderByIndex);
        if (limitIndex != -1) endIndex = Math.min(endIndex, limitIndex);
        
        return sql.substring(whereIndex + 7, endIndex).trim();
    }
    
    /**
     * Collects all filter parameters from the query.
     */
    private void collectAllFilterParameters(CrossSchemaQueryBuilder queryBuilder, List<Object> params) {
        // Root schema filters
        for (QueryFilter filter : queryBuilder.getFilters()) {
            if (filter.getValue() != null) {
                if (filter.getValue() instanceof Collection<?> collection) {
                    params.addAll(collection);
                } else {
                    params.add(filter.getValue());
                }
            }
        }
        
        // Join filters
        for (JoinOperation join : queryBuilder.getJoins()) {
            for (QueryFilter filter : join.getFilters()) {
                if (filter.getValue() != null) {
                    if (filter.getValue() instanceof Collection<?> collection) {
                        params.addAll(collection);
                    } else {
                        params.add(filter.getValue());
                    }
                }
            }
        }
    }
    
    /**
     * Extracts schema data from a row map.
     */
    private Object extractSchemaDataFromRow(Map<String, Object> row, PlayerDataSchema<?> schema, String tableAlias) {
        try {
            if (!(schema instanceof TableSchema)) {
                return null;
            }
            
            // Use reflection to create instance and populate fields
            Class<?> type = schema.type();
            Object instance = type.getDeclaredConstructor().newInstance();
            
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) ||
                    java.lang.reflect.Modifier.isTransient(field.getModifiers())) {
                    continue;
                }
                
                field.setAccessible(true);
                String columnName = field.getName();
                String aliasedColumn = tableAlias + "_" + columnName;
                
                Object value = row.get(aliasedColumn);
                if (value != null) {
                    // Convert value to appropriate type
                    Object convertedValue = convertValue(value, field.getType());
                    field.set(instance, convertedValue);
                }
            }
            
            return instance;
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to extract schema data for " + schema.schemaKey(), e);
            return null;
        }
    }
    
    /**
     * Converts a value to the specified type.
     */
    private Object convertValue(Object value, Class<?> targetType) {
        if (value == null) return null;
        
        if (targetType == UUID.class && value instanceof String) {
            return UUID.fromString((String) value);
        } else if (targetType.isEnum() && value instanceof String) {
            return Enum.valueOf((Class<? extends Enum>) targetType, (String) value);
        } else if (targetType.isAssignableFrom(value.getClass())) {
            return value;
        }
        
        // Additional conversions as needed
        return value;
    }
}