package sh.harold.fulcrum.playerdata;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class AutoTableSchema<T> extends TableSchema<T> {
    private final Class<T> type;
    private final String tableName;
    private final Map<String, ColumnInfo> columns;

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

    public AutoTableSchema(Class<T> type) {
        this.type = type;
        var tableAnn = type.getAnnotation(Table.class);
        if (tableAnn == null) throw new IllegalArgumentException("Missing @Table annotation");
        this.tableName = tableAnn.value();
        this.columns = new LinkedHashMap<>();
        for (Field field : type.getDeclaredFields()) {
            var colAnn = field.getAnnotation(Column.class);
            if (colAnn == null) continue;
            String sqlType = mapType(field.getType());
            columns.put(field.getName(), new ColumnInfo(field.getName(), sqlType, colAnn.primary()));
        }
    }

    private String mapType(Class<?> type) {
        if (type == UUID.class) return "UUID";
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
        if (pk != null) sb.append(", PRIMARY KEY (").append(pk).append(")");
        sb.append(");");
        return sb.toString();
    }

    @Override
    public String schemaKey() { return tableName; }
    @Override
    public Class<T> type() { return type; }
    @Override
    public T load(UUID uuid) { return null; }
    @Override
    public void save(UUID uuid, T data) {}
}
