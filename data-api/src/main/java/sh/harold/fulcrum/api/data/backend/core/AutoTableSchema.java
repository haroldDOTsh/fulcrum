package sh.harold.fulcrum.api.data.backend.core;

import sh.harold.fulcrum.api.data.annotation.*;
import sh.harold.fulcrum.api.data.backend.sql.SqlDialect;
import sh.harold.fulcrum.api.data.impl.ForeignKey;
import sh.harold.fulcrum.api.data.impl.SchemaVersion;
import sh.harold.fulcrum.api.data.impl.Table;
import sh.harold.fulcrum.api.data.impl.TableSchema;
import sh.harold.fulcrum.api.data.registry.PlayerDataRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

public class AutoTableSchema<T> extends TableSchema<T> {
    private final Class<T> type;
    private final String tableName;
    private final Map<String, ColumnInfo> columns;
    private final ColumnInfo primaryKey;
    private final Map<String, Field> fieldMap;
    private final Connection testConnection;
    private final SqlDialect dialect;
    private final List<ForeignKeyInfo> foreignKeys = new ArrayList<>();
    private final List<PendingForeignKey> pendingForeignKeys = new ArrayList<>();
    private final List<IndexInfo> indexes = new ArrayList<>();
    private static java.util.function.Supplier<Connection> connectionProvider;


    /**
     * Developer-facing constructor. Use this for all plugin and API code.
     * The backend will handle all connection management.
     *
     * @param type POJO class
     */
    public AutoTableSchema(Class<T> type) {
        this(type, null);
    }

    /**
     * INTERNAL/TEST USE ONLY: Allows injection of a test connection for schema operations.
     * Do not use in plugin or production code. The backend will manage connections.
     *
     * @param type           POJO class
     * @param testConnection optional test connection (for tests/migrations only)
     */
    public AutoTableSchema(Class<T> type, Connection testConnection) {
        this.type = type;
        this.testConnection = testConnection;
        this.dialect = sh.harold.fulcrum.api.data.backend.sql.SqlDialectProvider.get();
        var tableAnn = type.getAnnotation(Table.class);
        if (tableAnn == null) throw new IllegalArgumentException("Missing @Table annotation");
        this.tableName = tableAnn.value();
        this.columns = new LinkedHashMap<>();
        this.fieldMap = new LinkedHashMap<>();
        // 1. Collect all eligible fields
        for (Field field : type.getDeclaredFields()) {
            int mods = field.getModifiers();
            if (Modifier.isStatic(mods) || Modifier.isTransient(mods)) continue;
            var colAnn = field.getAnnotation(Column.class);
            String colName = colAnn != null && !colAnn.value().isEmpty() ? colAnn.value() : field.getName();
            String sqlType = this.dialect.getSqlType(field.getType());
            boolean isPrimary = colAnn != null && colAnn.primary();
            PrimaryKeyGeneration generation = PrimaryKeyGeneration.NONE;
            if (colAnn != null) {
                generation = colAnn.generation();
            }
            columns.put(colName, new ColumnInfo(colName, sqlType, isPrimary, generation));
            fieldMap.put(colName, field);
            // Foreign key detection and validation
            var fkAnn = field.getAnnotation(ForeignKey.class);
            if (fkAnn != null) {
                pendingForeignKeys.add(new PendingForeignKey(colName, fkAnn.references(), fkAnn.field(), fkAnn.onDelete(), fkAnn.onUpdate()));
            }
            
            // Process field-level @Index annotations
            var indexAnn = field.getAnnotation(Index.class);
            if (indexAnn != null) {
                String indexName = indexAnn.name().isEmpty() ?
                    generateIndexName(tableName, colName) : indexAnn.name();
                indexes.add(new IndexInfo(indexName, new String[]{colName},
                    new IndexOrder[]{indexAnn.order()}, indexAnn.unique()));
            }
        }
        
        // Process class-level @Indexes annotations
        processCompositeIndexes(type);
        // 2. If no @Column(primary = true), fallback to id/uuid
        ColumnInfo pkInfo = columns.values().stream().filter(c -> c.primary).findFirst().orElse(null);
        if (pkInfo == null) {
            for (var entry : columns.entrySet()) {
                String name = entry.getKey().toLowerCase(Locale.ROOT);
                Field f = fieldMap.get(entry.getKey());
                if ((name.equals("id") || name.equals("uuid")) && f.getType() == UUID.class) {
                    pkInfo = entry.getValue();
                    break;
                }
            }
        }
        if (pkInfo == null) throw new IllegalStateException("No primary key found for " + type.getName());
        // Validate PK generation for UUID
        Field pkField = fieldMap.get(pkInfo.name);
        if (pkField.getType() == UUID.class) {
            if (pkInfo.generation == null)
                throw new IllegalStateException("Primary key UUID field must specify generation mode");
            // NONE is allowed, but must be set manually at save time
        }
        this.primaryKey = pkInfo;
    }

    public String getCreateTableSql() {
        // Resolve all pending foreign keys now
        foreignKeys.clear();
        for (var pfk : pendingForeignKeys) {
            var refSchema = PlayerDataRegistry.allSchemas().stream()
                    .filter(s -> s.type().equals(pfk.refClass))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Referenced schema not registered: " + pfk.refClass.getName()));
            String refTable = refSchema.schemaKey();
            String refCol = null;
            Field refFieldObj = null;
            for (Field f : pfk.refClass.getDeclaredFields()) {
                var refColAnn = f.getAnnotation(Column.class);
                String candidate = refColAnn != null && !refColAnn.value().isEmpty() ? refColAnn.value() : f.getName();
                if (candidate.equals(pfk.refField)) {
                    refCol = candidate;
                    refFieldObj = f;
                    break;
                }
            }
            if (refCol == null) {
                for (Field f : pfk.refClass.getDeclaredFields()) {
                    var refColAnn = f.getAnnotation(Column.class);
                    if (refColAnn != null && refColAnn.primary()) {
                        refCol = !refColAnn.value().isEmpty() ? refColAnn.value() : f.getName();
                        refFieldObj = f;
                        break;
                    }
                }
            }
            if (refCol == null || refFieldObj == null)
                throw new IllegalArgumentException("Referenced field not found: " + pfk.refField + " in " + pfk.refClass.getName());
            Field localField = fieldMap.get(pfk.columnName);
            if (localField == null) throw new IllegalArgumentException("Local field not found: " + pfk.columnName);
            if (!localField.getType().equals(refFieldObj.getType())) {
                throw new IllegalArgumentException("Foreign key type mismatch: " + localField.getType().getName() + " vs " + refFieldObj.getType().getName());
            }
            foreignKeys.add(new ForeignKeyInfo(pfk.columnName, refTable, refCol, pfk.onDelete, pfk.onUpdate));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ").append(dialect.quoteIdentifier(tableName)).append(" (");
        String pk = null;
        boolean first = true;
        for (var col : columns.values()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(dialect.quoteIdentifier(col.name)).append(" ").append(col.sqlType);
            if (col.primary) {
                if (pk != null) throw new IllegalStateException("Multiple primary keys");
                pk = col.name;
            }
        }
        if (pk == null) pk = primaryKey.name;
        if (pk != null) sb.append(", PRIMARY KEY (").append(dialect.quoteIdentifier(pk)).append(")");
        // Emit foreign key constraints
        for (var fk : foreignKeys) {
            sb.append(", FOREIGN KEY (")
                    .append(dialect.quoteIdentifier(fk.columnName))
                    .append(") REFERENCES ")
                    .append(dialect.quoteIdentifier(fk.referencedTable))
                    .append("(")
                    .append(dialect.quoteIdentifier(fk.referencedColumn))
                    .append(")");
            if (!fk.onDelete.isEmpty()) sb.append(" ON DELETE ").append(fk.onDelete);
            if (!fk.onUpdate.isEmpty()) sb.append(" ON UPDATE ").append(fk.onUpdate);
        }
        sb.append(");");
        return sb.toString();
    }

    public int getSchemaVersion() {
        var ann = type.getAnnotation(SchemaVersion.class);
        return ann != null ? ann.value() : 1;
    }

    public void createTable(Connection conn) throws Exception {
        String sql = getCreateTableSql();
        try (var stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
        
        // Create indexes
        for (String indexSql : getCreateIndexStatements()) {
            try (var stmt = conn.createStatement()) {
                stmt.execute(indexSql);
            }
        }
        
        String versionSql = "INSERT OR REPLACE INTO " + dialect.quoteIdentifier("schema_versions") +
                " (" + dialect.quoteIdentifier("table_name") + ", " + dialect.quoteIdentifier("version") + ") VALUES ('" + tableName + "', " + getSchemaVersion() + ")";
        try (var stmt = conn.createStatement()) {
            stmt.execute(versionSql);
        }
    }

    /**
     * Ensures the schema_versions table exists, then creates the main table, then enforces schema versioning rules.
     * Throws if migration or downgrade is detected.
     */
    public void ensureTableAndVersion(java.sql.Connection conn) throws Exception {
        // 1. Create schema_versions table if needed (must be first)
        String createVersionsTable = "CREATE TABLE IF NOT EXISTS " + dialect.quoteIdentifier("schema_versions") +
                " (" + dialect.quoteIdentifier("table_name") + " TEXT PRIMARY KEY, " + dialect.quoteIdentifier("version") + " INTEGER NOT NULL)";
        try (var stmt = conn.createStatement()) {
            stmt.execute(createVersionsTable);
        }
        // 2. Create main table if needed
        createTable(conn);
        // 3. Check version
        int currentVersion = getSchemaVersion();
        Integer storedVersion = null;
        String selectVersion = "SELECT " + dialect.quoteIdentifier("version") + " FROM " + dialect.quoteIdentifier("schema_versions") + " WHERE " + dialect.quoteIdentifier("table_name") + " = ?";
        try (var ps = conn.prepareStatement(selectVersion)) {
            ps.setString(1, tableName);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    storedVersion = rs.getInt(1);
                }
            }
        }
        if (storedVersion == null) {
            // First time: insert version
            String insertVersion = "INSERT OR REPLACE INTO " + dialect.quoteIdentifier("schema_versions") +
                    " (" + dialect.quoteIdentifier("table_name") + ", " + dialect.quoteIdentifier("version") + ") VALUES (?, ?)";
            try (var ps = conn.prepareStatement(insertVersion)) {
                ps.setString(1, tableName);
                ps.setInt(2, currentVersion);
                ps.executeUpdate();
            }
        } else if (storedVersion == currentVersion) {
            // OK
        } else if (storedVersion < currentVersion) {
            throw new RuntimeException("Schema migration is not yet supported for table '" + tableName + "': stored version " + storedVersion + " < current version " + currentVersion);
        } else {
            throw new RuntimeException("Schema version for table '" + tableName + "' is newer (" + storedVersion + ") than supported by this code (" + currentVersion + "). Please update your plugin.");
        }
    }

    public T load(UUID uuid) {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = getConnection();
            String sql = "SELECT * FROM " + dialect.quoteIdentifier(tableName) + " WHERE " + dialect.quoteIdentifier(primaryKey.name) + " = ? LIMIT 1";
            ps = conn.prepareStatement(sql);
            ps.setObject(1, uuid);
            rs = ps.executeQuery();
            if (!rs.next()) return null;
            T instance = instantiate();
            for (var entry : columns.entrySet()) {
                String col = entry.getKey();
                Field field = fieldMap.get(col);
                field.setAccessible(true);
                Object value = getValueFromResultSet(rs, col, field.getType());
                field.set(instance, value);
            }
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load " + type.getSimpleName() + " for UUID " + uuid + ": " + e, e);
        } finally {
            try {
                if (rs != null) rs.close();
            } catch (Exception ignored) {
            }
            try {
                if (ps != null) ps.close();
            } catch (Exception ignored) {
            }
            // do not close conn
        }
    }

    public void save(UUID uuid, T data) {
        if (uuid == null && primaryKey.generation == PrimaryKeyGeneration.PLAYER_UUID)
            throw new RuntimeException("Primary key (UUID) must not be null for save() on " + tableName);
        // Inject PK if needed
        Field pkField = fieldMap.get(primaryKey.name);
        pkField.setAccessible(true);
        try {
            Object current = pkField.get(data);
            if (current == null) {
                UUID effectiveId = null;
                if (primaryKey.generation == PrimaryKeyGeneration.PLAYER_UUID) {
                    if (uuid == null) throw new IllegalStateException("PLAYER_UUID generation requires UUID");
                    effectiveId = uuid;
                } else if (primaryKey.generation == PrimaryKeyGeneration.RANDOM_UUID) {
                    effectiveId = UUID.randomUUID();
                } else if (primaryKey.generation == PrimaryKeyGeneration.NONE) {
                    throw new IllegalStateException("Primary key must be set manually for NONE generation mode");
                }
                if (effectiveId != null) pkField.set(data, effectiveId);
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to set primary key field", e);
        }
        StringBuilder sql = new StringBuilder();
        sql.append("INSERT OR REPLACE INTO ").append(dialect.quoteIdentifier(tableName)).append(" (");
        StringJoiner cols = new StringJoiner(", ");
        StringJoiner vals = new StringJoiner(", ");
        for (var col : columns.keySet()) {
            cols.add(dialect.quoteIdentifier(col));
            vals.add("?");
        }
        sql.append(cols).append(") VALUES (").append(vals).append(")");
        Connection conn = null;
        PreparedStatement ps = null;
        try {
            conn = getConnection();
            ps = conn.prepareStatement(sql.toString());
            System.out.println("[DEBUG] save: SQL=" + sql);
            int idx = 1;
            for (var col : columns.keySet()) {
                Field field = fieldMap.get(col);
                field.setAccessible(true);
                Object value = field.get(data);
                System.out.println("[DEBUG] save: col=" + col + ", value=" + value);
                setPreparedStatementValue(ps, idx++, value, field.getType());
            }
            ps.executeUpdate();
        } catch (Exception e) {
            System.out.println("[DEBUG] save: Exception: " + e);
            throw new RuntimeException("Failed to save " + type.getSimpleName() + " for UUID " + uuid + ": " + e, e);
        } finally {
            try {
                if (ps != null) ps.close();
            } catch (Exception ignored) {
            }
            // do not close conn
        }
    }

    public T instantiate() throws Exception {
        try {
            var ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("No no-arg constructor for " + type.getName());
        }
    }

    private Object getValueFromResultSet(ResultSet rs, String col, Class<?> type) throws Exception {
        if (type == UUID.class) {
            var obj = rs.getString(col);
            return obj == null ? null : UUID.fromString(obj);
        }
        if (type == String.class) return rs.getString(col);
        if (type == int.class || type == Integer.class) return rs.getInt(col);
        if (type == long.class || type == Long.class) return rs.getLong(col);
        if (type == boolean.class || type == Boolean.class) return rs.getBoolean(col);
        if (type.isEnum()) {
            String enumName = rs.getString(col);
            if (enumName == null) return null;
            // Convert string back to enum
            return Enum.valueOf((Class<? extends Enum>) type, enumName);
        }
        throw new IllegalArgumentException("Unsupported type: " + type.getName());
    }

    private void setPreparedStatementValue(PreparedStatement ps, int idx, Object value, Class<?> type) throws Exception {
        System.out.println("[DEBUG] setPreparedStatementValue: idx=" + idx + ", value=" + value + ", type=" + type.getName() + ", isEnum=" + type.isEnum());

        if (type == UUID.class) ps.setString(idx, value == null ? null : value.toString());
        else if (type == String.class) ps.setString(idx, (String) value);
        else if (type == int.class || type == Integer.class) ps.setInt(idx, (Integer) value);
        else if (type == long.class || type == Long.class) ps.setLong(idx, (Long) value);
        else if (type == boolean.class || type == Boolean.class) ps.setBoolean(idx, (Boolean) value);
        else if (type.isEnum()) {
            ps.setString(idx, value == null ? null : ((Enum<?>) value).name());
        } else throw new IllegalArgumentException("Unsupported type: " + type.getName());
    }

    protected Connection getConnection() throws Exception {
        if (testConnection != null) return testConnection;
        if (connectionProvider != null) {
            Connection conn = connectionProvider.get();
            if (conn != null) return conn;
        }
        throw new UnsupportedOperationException("No connection available - connection provider not set");
    }

    /**
     * Sets the global connection provider for AutoTableSchema instances.
     * This should be called during backend initialization.
     *
     * @param provider The connection provider function
     */
    public static void setConnectionProvider(java.util.function.Supplier<Connection> provider) {
        connectionProvider = provider;
    }

    @Override
    public Class<T> type() {
        return type;
    }

    @Override
    public String schemaKey() {
        return tableName;
    }

    @Override
    public T deserialize(ResultSet rs) {
        try {
            T instance = instantiate();
            for (var entry : columns.entrySet()) {
                String col = entry.getKey();
                Field field = fieldMap.get(col);
                field.setAccessible(true);
                Object value = getValueFromResultSet(rs, col, field.getType());
                field.set(instance, value);
            }
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize " + type.getSimpleName() + " from ResultSet", e);
        }
    }

    /**
     * Generates CREATE INDEX statements for all indexes defined on this table.
     *
     * @return List of CREATE INDEX SQL statements
     */
    public List<String> getCreateIndexStatements() {
        List<String> statements = new ArrayList<>();
        for (IndexInfo index : indexes) {
            if (index.columnNames.length == 1) {
                statements.add(dialect.createIndexStatement(
                    index.name, tableName, index.columnNames[0],
                    index.orders[0], index.unique));
            } else {
                statements.add(dialect.createCompositeIndexStatement(
                    index.name, tableName, index.columnNames,
                    index.orders, index.unique));
            }
        }
        return statements;
    }

    /**
     * Generates an index name following the convention: idx_{tableName}_{columnName}
     * For composite indexes: idx_{tableName}_{col1}_{col2}...
     */
    private String generateIndexName(String tableName, String... columnNames) {
        StringBuilder sb = new StringBuilder("idx_");
        sb.append(tableName);
        for (String colName : columnNames) {
            sb.append("_").append(colName);
        }
        return sb.toString();
    }

    /**
     * Processes class-level @Indexes annotations to extract composite index definitions
     */
    private void processCompositeIndexes(Class<T> type) {
        Indexes indexesAnn = type.getAnnotation(Indexes.class);
        if (indexesAnn != null) {
            for (CompositeIndex compositeIndex : indexesAnn.value()) {
                String indexName = compositeIndex.name().isEmpty() ?
                    generateIndexName(tableName, compositeIndex.fields()) : compositeIndex.name();
                
                // Validate that all referenced fields exist
                for (String fieldName : compositeIndex.fields()) {
                    if (!fieldMap.containsKey(fieldName)) {
                        throw new IllegalArgumentException("Composite index references non-existent field: " + fieldName + " in " + type.getName());
                    }
                }
                
                indexes.add(new IndexInfo(indexName, compositeIndex.fields(),
                    compositeIndex.orders(), compositeIndex.unique()));
            }
        }
    }

    private static class ColumnInfo {
        final String name;
        final String sqlType;
        final boolean primary;
        final PrimaryKeyGeneration generation;

        ColumnInfo(String name, String sqlType, boolean primary, PrimaryKeyGeneration generation) {
            this.name = name;
            this.sqlType = sqlType;
            this.primary = primary;
            this.generation = generation;
        }
    }

    // Foreign key metadata for DDL and future runtime use
    private static class ForeignKeyInfo {
        final String columnName;
        final String referencedTable;
        final String referencedColumn;
        final String onDelete;
        final String onUpdate;

        ForeignKeyInfo(String columnName, String referencedTable, String referencedColumn, String onDelete, String onUpdate) {
            this.columnName = columnName;
            this.referencedTable = referencedTable;
            this.referencedColumn = referencedColumn;
            this.onDelete = onDelete;
            this.onUpdate = onUpdate;
        }
    }

    private static class PendingForeignKey {
        final String columnName;
        final Class<?> refClass;
        final String refField;
        final String onDelete;
        final String onUpdate;

        PendingForeignKey(String columnName, Class<?> refClass, String refField, String onDelete, String onUpdate) {
            this.columnName = columnName;
            this.refClass = refClass;
            this.refField = refField;
            this.onDelete = onDelete;
            this.onUpdate = onUpdate;
        }
    }

    /**
     * Metadata for database indexes
     */
    private static class IndexInfo {
        final String name;
        final String[] columnNames;
        final IndexOrder[] orders;
        final boolean unique;

        IndexInfo(String name, String[] columnNames, IndexOrder[] orders, boolean unique) {
            this.name = name;
            this.columnNames = columnNames;
            this.orders = orders.length > 0 ? orders :
                Arrays.stream(columnNames).map(c -> IndexOrder.ASC).toArray(IndexOrder[]::new);
            this.unique = unique;
        }
    }
@Override
public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null || getClass() != obj.getClass()) return false;
    AutoTableSchema<?> that = (AutoTableSchema<?>) obj;
    return type.equals(that.type);
}

@Override
public int hashCode() {
    return type.hashCode();
}
}
