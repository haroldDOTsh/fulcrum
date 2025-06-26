package sh.harold.fulcrum.playerdata;

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

    public AutoTableSchema(Class<T> type, Connection testConnection) {
        this.type = type;
        this.testConnection = testConnection;
        var tableAnn = type.getAnnotation(Table.class);
        if (tableAnn == null) throw new IllegalArgumentException("Missing @Table annotation");
        this.tableName = tableAnn.value();
        this.columns = new LinkedHashMap<>();
        this.fieldMap = new LinkedHashMap<>();
        Field pkField = null;
        // 1. Collect all eligible fields
        for (Field field : type.getDeclaredFields()) {
            int mods = field.getModifiers();
            if (Modifier.isStatic(mods) || Modifier.isTransient(mods)) continue;
            var colAnn = field.getAnnotation(Column.class);
            String colName = colAnn != null && !colAnn.value().isEmpty() ? colAnn.value() : field.getName();
            String sqlType = mapType(field.getType());
            boolean isPrimary = colAnn != null && colAnn.primary();
            columns.put(colName, new ColumnInfo(colName, sqlType, isPrimary));
            fieldMap.put(colName, field);
            if (isPrimary) pkField = field;
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

    private String mapType(Class<?> type) {
        if (type == UUID.class) return "TEXT"; // SQLite does not support UUID natively
        if (type == String.class) return "TEXT";
        if (type == int.class || type == Integer.class) return "INT";
        if (type == long.class || type == Long.class) return "BIGINT";
        if (type == boolean.class || type == Boolean.class) return "BOOLEAN";
        throw new IllegalArgumentException("Unsupported type: " + type.getName());
    }

    public String getCreateTableSql() {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(tableName).append(" (");
        String pk = null;
        boolean first = true;
        for (var col : columns.values()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(col.name).append(" ").append(col.sqlType);
            if (col.primary) {
                if (pk != null) throw new IllegalStateException("Multiple primary keys");
                pk = col.name;
            }
        }
        if (pk == null) pk = primaryKey.name;
        if (pk != null) sb.append(", PRIMARY KEY (").append(pk).append(")");
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
        String versionSql = "INSERT OR REPLACE INTO schema_versions (table_name, version) VALUES ('" + tableName + "', " + getSchemaVersion() + ")";
        try (var stmt = conn.createStatement()) {
            stmt.execute(versionSql);
        }
    }

    @Override
    public String schemaKey() { return tableName; }
    @Override
    public Class<T> type() { return type; }

    @Override
    public T load(UUID uuid) {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = getConnection();
            ps = conn.prepareStatement("SELECT * FROM " + tableName + " WHERE " + primaryKey.name + " = ? LIMIT 1");
            System.out.println("[DEBUG] load: SQL=SELECT * FROM " + tableName + " WHERE " + primaryKey.name + " = ? LIMIT 1, uuid=" + uuid);
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
            try { if (rs != null) rs.close(); } catch (Exception ignored) {}
            try { if (ps != null) ps.close(); } catch (Exception ignored) {}
            // do not close conn
        }
    }

    @Override
    public void save(UUID uuid, T data) {
        if (uuid == null) throw new RuntimeException("Primary key (UUID) must not be null for save() on " + tableName);
        StringBuilder sql = new StringBuilder();
        sql.append("INSERT OR REPLACE INTO ").append(tableName).append(" (");
        StringJoiner cols = new StringJoiner(", ");
        StringJoiner vals = new StringJoiner(", ");
        for (var col : columns.keySet()) {
            cols.add(col);
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
            try { if (ps != null) ps.close(); } catch (Exception ignored) {}
            // do not close conn
        }
    }

    private T instantiate() throws Exception {
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
}
