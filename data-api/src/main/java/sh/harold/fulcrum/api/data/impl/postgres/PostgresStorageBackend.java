package sh.harold.fulcrum.api.data.impl.postgres;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.data.impl.DocumentImpl;
import sh.harold.fulcrum.api.data.query.Query;
import sh.harold.fulcrum.api.data.storage.StorageBackend;
import sh.harold.fulcrum.api.data.impl.QueryImpl;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PostgreSQL implementation of StorageBackend.
 * Provides document operations using PostgreSQL with JSONB support for flexible data storage.
 */
public class PostgresStorageBackend implements StorageBackend {
    
    private static final String ID_FIELD = "id";
    private static final String DATA_FIELD = "data";
    private final PostgresConnectionAdapter connectionAdapter;
    private final Gson gson = new Gson();
    private final Map<String, Boolean> tableExistence = new ConcurrentHashMap<>();
    
    public PostgresStorageBackend(PostgresConnectionAdapter connectionAdapter) {
        this.connectionAdapter = connectionAdapter;
    }
    
    @Override
    public CompletableFuture<Document> getDocument(String collection, String id) {
        return CompletableFuture.supplyAsync(() -> {
            ensureTableExists(collection);
            String sql = "SELECT " + DATA_FIELD + " FROM " + sanitizeTableName(collection) + " WHERE " + ID_FIELD + " = ?";
            
            try (Connection conn = connectionAdapter.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                
                stmt.setString(1, id);
                ResultSet rs = stmt.executeQuery();
                
                Map<String, Object> data = null;
                if (rs.next()) {
                    String jsonData = rs.getString(DATA_FIELD);
                    if (jsonData != null) {
                        JsonObject jsonObject = JsonParser.parseString(jsonData).getAsJsonObject();
                        data = gson.fromJson(jsonObject, Map.class);
                    }
                }
                
                return new DocumentImpl(collection, id, data, this);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get document: " + e.getMessage(), e);
            }
        });
    }
    
    @Override
    public CompletableFuture<Void> saveDocument(String collection, String id, Map<String, Object> data) {
        return CompletableFuture.runAsync(() -> {
            ensureTableExists(collection);
            String jsonData = gson.toJson(data);
            String sql = "INSERT INTO " + sanitizeTableName(collection) + " (" + ID_FIELD + ", " + DATA_FIELD + 
                        ") VALUES (?, ?::jsonb) ON CONFLICT (" + ID_FIELD + ") DO UPDATE SET " + 
                        DATA_FIELD + " = EXCLUDED." + DATA_FIELD + ", updated_at = CURRENT_TIMESTAMP";
            
            try (Connection conn = connectionAdapter.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                
                stmt.setString(1, id);
                stmt.setString(2, jsonData);
                stmt.executeUpdate();
                
            } catch (SQLException e) {
                throw new RuntimeException("Failed to save document: " + e.getMessage(), e);
            }
        });
    }
    
    @Override
    public CompletableFuture<Boolean> deleteDocument(String collection, String id) {
        return CompletableFuture.supplyAsync(() -> {
            ensureTableExists(collection);
            String sql = "DELETE FROM " + sanitizeTableName(collection) + " WHERE " + ID_FIELD + " = ?";
            
            try (Connection conn = connectionAdapter.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                
                stmt.setString(1, id);
                int rowsAffected = stmt.executeUpdate();
                return rowsAffected > 0;
                
            } catch (SQLException e) {
                throw new RuntimeException("Failed to delete document: " + e.getMessage(), e);
            }
        });
    }
    
    @Override
    public CompletableFuture<List<Document>> query(String collection, Query query) {
        return CompletableFuture.supplyAsync(() -> {
            ensureTableExists(collection);
            
            StringBuilder sql = new StringBuilder("SELECT " + ID_FIELD + ", " + DATA_FIELD + " FROM " + sanitizeTableName(collection));
            List<Object> params = new ArrayList<>();
            
            if (query instanceof QueryImpl) {
                QueryImpl queryImpl = (QueryImpl) query;
                String whereClause = buildWhereClause(queryImpl, params);
                if (!whereClause.isEmpty()) {
                    sql.append(" WHERE ").append(whereClause);
                }
                
                String orderBy = buildOrderByClause(queryImpl);
                if (!orderBy.isEmpty()) {
                    sql.append(" ORDER BY ").append(orderBy);
                }
                
                Integer limit = getLimit(queryImpl);
                if (limit != null && limit > 0) {
                    sql.append(" LIMIT ").append(limit);
                }
                
                Integer skip = getSkip(queryImpl);
                if (skip != null && skip > 0) {
                    sql.append(" OFFSET ").append(skip);
                }
            }
            
            List<Document> results = new ArrayList<>();
            try (Connection conn = connectionAdapter.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                
                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }
                
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    String docId = rs.getString(ID_FIELD);
                    String jsonData = rs.getString(DATA_FIELD);
                    Map<String, Object> data = null;
                    if (jsonData != null) {
                        JsonObject jsonObject = JsonParser.parseString(jsonData).getAsJsonObject();
                        data = gson.fromJson(jsonObject, Map.class);
                    }
                    results.add(new DocumentImpl(collection, docId, data, this));
                }
                
            } catch (SQLException e) {
                throw new RuntimeException("Failed to execute query: " + e.getMessage(), e);
            }
            
            return results;
        });
    }
    
    @Override
    public CompletableFuture<Long> count(String collection, Query query) {
        return CompletableFuture.supplyAsync(() -> {
            ensureTableExists(collection);
            
            StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM " + sanitizeTableName(collection));
            List<Object> params = new ArrayList<>();
            
            if (query instanceof QueryImpl) {
                QueryImpl queryImpl = (QueryImpl) query;
                String whereClause = buildWhereClause(queryImpl, params);
                if (!whereClause.isEmpty()) {
                    sql.append(" WHERE ").append(whereClause);
                }
            }
            
            try (Connection conn = connectionAdapter.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                
                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }
                
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getLong(1);
                }
                return 0L;
                
            } catch (SQLException e) {
                throw new RuntimeException("Failed to count documents: " + e.getMessage(), e);
            }
        });
    }
    
    @Override
    public CompletableFuture<List<Document>> getAllDocuments(String collection) {
        return CompletableFuture.supplyAsync(() -> {
            ensureTableExists(collection);
            String sql = "SELECT " + ID_FIELD + ", " + DATA_FIELD + " FROM " + sanitizeTableName(collection);
            
            List<Document> results = new ArrayList<>();
            try (Connection conn = connectionAdapter.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                
                while (rs.next()) {
                    String docId = rs.getString(ID_FIELD);
                    String jsonData = rs.getString(DATA_FIELD);
                    Map<String, Object> data = null;
                    if (jsonData != null) {
                        JsonObject jsonObject = JsonParser.parseString(jsonData).getAsJsonObject();
                        data = gson.fromJson(jsonObject, Map.class);
                    }
                    results.add(new DocumentImpl(collection, docId, data, this));
                }
                
            } catch (SQLException e) {
                throw new RuntimeException("Failed to get all documents: " + e.getMessage(), e);
            }
            
            return results;
        });
    }
    /**
     * Update a specific field in a document using JSONB operators.
     */
    public CompletableFuture<Void> updateField(String collection, String id, String path, Object value) {
        return CompletableFuture.runAsync(() -> {
            ensureTableExists(collection);
            String jsonPath = convertToJsonPath(path);
            String sql = "UPDATE " + sanitizeTableName(collection) + 
                        " SET " + DATA_FIELD + " = jsonb_set(" + DATA_FIELD + ", ?, ?::jsonb, true), " +
                        "updated_at = CURRENT_TIMESTAMP WHERE " + ID_FIELD + " = ?";
            
            try (Connection conn = connectionAdapter.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                
                stmt.setString(1, jsonPath);
                stmt.setString(2, gson.toJson(value));
                stmt.setString(3, id);
                stmt.executeUpdate();
                
            } catch (SQLException e) {
                throw new RuntimeException("Failed to update field: " + e.getMessage(), e);
            }
        });
    }
    
    /**
     * Increment a numeric field atomically.
     */
    public CompletableFuture<Void> incrementField(String collection, String id, String path, Number amount) {
        return CompletableFuture.runAsync(() -> {
            ensureTableExists(collection);
            String jsonPath = convertToJsonPath(path);
            String sql = "UPDATE " + sanitizeTableName(collection) + 
                        " SET " + DATA_FIELD + " = jsonb_set(" + DATA_FIELD + ", ?, " +
                        "(COALESCE((" + DATA_FIELD + " #> ?)::numeric, 0) + ?)::text::jsonb), " +
                        "updated_at = CURRENT_TIMESTAMP WHERE " + ID_FIELD + " = ?";
            
            try (Connection conn = connectionAdapter.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                
                stmt.setString(1, jsonPath);
                stmt.setArray(2, conn.createArrayOf("text", jsonPath.substring(1, jsonPath.length() - 1).split(",")));
                stmt.setBigDecimal(3, new java.math.BigDecimal(amount.toString()));
                stmt.setString(4, id);
                stmt.executeUpdate();
                
            } catch (SQLException e) {
                throw new RuntimeException("Failed to increment field: " + e.getMessage(), e);
            }
        });
    }
    
    /**
     * Ensure the table exists for the given collection.
     */
    private void ensureTableExists(String collection) {
        String tableName = sanitizeTableName(collection);
        if (tableExistence.containsKey(tableName)) {
            return;
        }
        
        String sql = "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
                    ID_FIELD + " VARCHAR(255) PRIMARY KEY, " +
                    DATA_FIELD + " JSONB NOT NULL, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)";
        
        try (Connection conn = connectionAdapter.getConnection();
             Statement stmt = conn.createStatement()) {
            
            stmt.execute(sql);
            
            // Create indexes for better performance
            String indexSql = "CREATE INDEX IF NOT EXISTS idx_" + tableName + "_data ON " + 
                             tableName + " USING GIN (" + DATA_FIELD + ")";
            stmt.execute(indexSql);
            
            String timestampIndexSql = "CREATE INDEX IF NOT EXISTS idx_" + tableName + "_updated_at ON " + 
                                      tableName + " (updated_at)";
            stmt.execute(timestampIndexSql);
            
            tableExistence.put(tableName, true);
            
        } catch (SQLException e) {
            throw new RuntimeException("Failed to ensure table exists: " + e.getMessage(), e);
        }
    }
    
    /**
     * Build WHERE clause from QueryImpl conditions.
     */
    private String buildWhereClause(QueryImpl queryImpl, List<Object> params) {
        List<QueryImpl.Condition> conditions = queryImpl.getConditions();
        if (conditions.isEmpty()) {
            return "";
        }
        
        StringBuilder where = new StringBuilder();
        QueryImpl.LogicalOperator lastOp = QueryImpl.LogicalOperator.AND;
        
        for (int i = 0; i < conditions.size(); i++) {
            QueryImpl.Condition condition = conditions.get(i);
            
            if (i > 0) {
                where.append(" ").append(lastOp).append(" ");
            }
            
            String conditionSql = buildConditionSql(condition, params);
            where.append(conditionSql);
            
            lastOp = condition.operator;
        }
        
        return where.toString();
    }
    
    /**
     * Build SQL for a single condition.
     */
    private String buildConditionSql(QueryImpl.Condition condition, List<Object> params) {
        String jsonPath = convertToJsonPath(condition.path);
        
        switch (condition.type) {
            case EQUAL:
                params.add(gson.toJson(condition.value));
                return DATA_FIELD + " @> ?::jsonb";
                
            case NOT_EQUAL:
                params.add(condition.value);
                return "NOT (" + DATA_FIELD + " #>> " + jsonPath + " = ?)";
                
            case CONTAINS:
                if (condition.value instanceof String) {
                    params.add("%" + condition.value + "%");
                    return DATA_FIELD + " #>> " + jsonPath + " LIKE ?";
                }
                return "1=1"; // Always true for unsupported types
                
            case GREATER_THAN:
                params.add(condition.value);
                return "(" + DATA_FIELD + " #> " + jsonPath + ")::numeric > ?";
                
            case LESS_THAN:
                params.add(condition.value);
                return "(" + DATA_FIELD + " #> " + jsonPath + ")::numeric < ?";
                
            case IN:
                if (condition.value instanceof List) {
                    List<?> values = (List<?>) condition.value;
                    StringBuilder inClause = new StringBuilder(DATA_FIELD + " #>> " + jsonPath + " IN (");
                    for (int i = 0; i < values.size(); i++) {
                        if (i > 0) inClause.append(", ");
                        inClause.append("?");
                        params.add(values.get(i));
                    }
                    inClause.append(")");
                    return inClause.toString();
                }
                return "1=1";
                
            default:
                return "1=1"; // Always true for unsupported operations
        }
    }
    
    /**
     * Build ORDER BY clause from QueryImpl.
     */
    private String buildOrderByClause(QueryImpl queryImpl) {
        try {
            java.lang.reflect.Field sortFieldField = QueryImpl.class.getDeclaredField("sortField");
            sortFieldField.setAccessible(true);
            String sortField = (String) sortFieldField.get(queryImpl);
            
            if (sortField == null) {
                return "";
            }
            
            java.lang.reflect.Field sortAscendingField = QueryImpl.class.getDeclaredField("sortAscending");
            sortAscendingField.setAccessible(true);
            boolean sortAscending = (boolean) sortAscendingField.get(queryImpl);
            
            String jsonPath = convertToJsonPath(sortField);
            return DATA_FIELD + " #> " + jsonPath + (sortAscending ? " ASC" : " DESC");
            
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * Get skip value from QueryImpl.
     */
    private Integer getSkip(QueryImpl queryImpl) {
        try {
            java.lang.reflect.Field skipField = QueryImpl.class.getDeclaredField("skipValue");
            skipField.setAccessible(true);
            return (Integer) skipField.get(queryImpl);
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Get limit value from QueryImpl.
     */
    private Integer getLimit(QueryImpl queryImpl) {
        try {
            java.lang.reflect.Field limitField = QueryImpl.class.getDeclaredField("limitValue");
            limitField.setAccessible(true);
            return (Integer) limitField.get(queryImpl);
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Convert dot notation path to PostgreSQL JSONB path.
     */
    private String convertToJsonPath(String path) {
        String[] parts = path.split("\\.");
        StringBuilder jsonPath = new StringBuilder("{");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) jsonPath.append(",");
            jsonPath.append(parts[i]);
        }
        jsonPath.append("}");
        return jsonPath.toString();
    }
    
    /**
     * Sanitize table name to prevent SQL injection.
     */
    private String sanitizeTableName(String collection) {
        return "fulcrum_" + collection.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
    }
    
    /**
     * Close the PostgreSQL connection.
     */
    public void close() {
        if (connectionAdapter != null) {
            connectionAdapter.close();
        }
    }
}
