package sh.harold.fulcrum.api.data.backend.core;

import sh.harold.fulcrum.api.data.backend.sql.SqlDialect;
import sh.harold.fulcrum.api.data.impl.*;
import sh.harold.fulcrum.api.data.registry.PlayerDataRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

/**
 * TableSchema implementation that generates SQL DDL/DML from annotated POJOs.
 * Now supports pluggable SQL dialects for cross-database compatibility.
 *
 * <p>Usage example:
 * <pre>
 *   SqlDialect dialect = new SqliteDialect();
 *   AutoTableSchema<MyPojo> schema = new AutoTableSchema<>(MyPojo.class, null, dialect);
 * </pre>
 *
 * <p>Legacy usage (deprecated):
 * <pre>
 *   AutoTableSchema<MyPojo> schema = new AutoTableSchema<>(MyPojo.class, null);
 * </pre>
 */
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

    /**
     * Constructs an AutoTableSchema for the given POJO class.
     * Uses the globally configured SqlDialectProvider for dialect selection.
     *
     * @param type           POJO class
     * @param testConnection optional test connection
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
            columns.put(colName, new ColumnInfo(colName, sqlType, isPrimary));
            fieldMap.put(colName, field);
            // Foreign key detection and validation
            var fkAnn = field.getAnnotation(ForeignKey.class);
            if (fkAnn != null) {
                pendingForeignKeys.add(new PendingForeignKey(colName, fkAnn.references(), fkAnn.field(), fkAnn.onDelete(), fkAnn.onUpdate()));
            }
        }
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
        sb.append("CREATE TABLE ").append(dialect.quoteIdentifier(tableName)).append(" (");
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
        String versionSql = "INSERT OR REPLACE INTO " + dialect.quoteIdentifier("schema_versions") +
                " (" + dialect.quoteIdentifier("table_name") + ", " + dialect.quoteIdentifier("version") + ") VALUES ('" + tableName + "', " + getSchemaVersion() + ")";
        try (var stmt = conn.createStatement()) {
            stmt.execute(versionSql);
        }
    }

    // Remove @Override from load/save, as TableSchema no longer declares them
    public T load(UUID uuid) {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = getConnection();
            String sql = "SELECT * FROM " + dialect.quoteIdentifier(tableName) + " WHERE " + dialect.quoteIdentifier(primaryKey.name) + " = ? LIMIT 1";
            ps = conn.prepareStatement(sql);
            System.out.println("[DEBUG] load: SQL=" + sql + ", uuid=" + uuid);
            ps.setObject(1, uuid);
            rs = ps.executeQuery();
            if (!rs.next()) return null;
            T instance = instantiate();
            for (var entry : columns.entrySet()) {
                String col = entry.getKey();
                Field field = fieldMap.get(col);
                field.setAccessible(true);
                Object value = getValueFromResultSet(rs, col, field.getType());
                System.out.println("[DEBUG] load: col=" + col + ", value=" + value);
                field.set(instance, value);
            }
            return instance;
        } catch (Exception e) {
            System.out.println("[DEBUG] load: Exception: " + e);
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
        if (uuid == null) throw new RuntimeException("Primary key (UUID) must not be null for save() on " + tableName);
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
        throw new IllegalArgumentException("Unsupported type: " + type.getName());
    }

    private void setPreparedStatementValue(PreparedStatement ps, int idx, Object value, Class<?> type) throws Exception {
        if (type == UUID.class) ps.setString(idx, value == null ? null : value.toString());
        else if (type == String.class) ps.setString(idx, (String) value);
        else if (type == int.class || type == Integer.class) ps.setInt(idx, (Integer) value);
        else if (type == long.class || type == Long.class) ps.setLong(idx, (Long) value);
        else if (type == boolean.class || type == Boolean.class) ps.setBoolean(idx, (Boolean) value);
        else throw new IllegalArgumentException("Unsupported type: " + type.getName());
    }

    // Override to provide a JDBC connection (to be implemented by the user or injected)
    protected Connection getConnection() throws Exception {
        if (testConnection != null) return testConnection;
        throw new UnsupportedOperationException("getConnection() must be implemented by the user");
    }

    @Override
    public Class<T> type() {
        return type;
    }

    @Override
    public String schemaKey() {
        return tableName;
    }

    private static class ColumnInfo {
        final String name;
        final String sqlType;
        final boolean primary;

        ColumnInfo(String name, String sqlType, boolean primary) {
            this.name = name;
            this.sqlType = sqlType;
            this.primary = primary;
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
}
