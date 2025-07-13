package sh.harold.fulcrum.api.data.backend.sql;

import sh.harold.fulcrum.api.data.annotation.IndexOrder;
import java.util.List;
import java.util.UUID;

public final class SqliteDialect implements SqlDialect {
    @Override
    public String getSqlType(Class<?> javaType) {
        if (javaType == UUID.class) return "TEXT";
        if (javaType == String.class) return "TEXT";
        if (javaType == int.class || javaType == Integer.class) return "INTEGER";
        if (javaType == long.class || javaType == Long.class) return "INTEGER";
        if (javaType == boolean.class || javaType == Boolean.class) return "BOOLEAN";
        if (javaType == double.class || javaType == Double.class) return "REAL";
        if (javaType == float.class || javaType == Float.class) return "REAL";

        // Handle enum types - store as TEXT (enum name)
        if (javaType.isEnum()) {
            return "TEXT";
        }

        throw new IllegalArgumentException("Unsupported Java type: " + javaType);
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    @Override
    public String getUpsertStatement(String table, List<String> columns, List<String> conflictKeys) {
        String cols = String.join(", ", columns.stream().map(this::quoteIdentifier).toList());
        String vals = String.join(", ", columns.stream().map(c -> "?").toList());
        return "INSERT OR REPLACE INTO " + quoteIdentifier(table) + " (" + cols + ") VALUES (" + vals + ")";
    }

    @Override
    public String createIndexStatement(String indexName, String tableName, String columnName,
                                     IndexOrder order, boolean unique) {
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE ");
        if (unique) {
            sql.append("UNIQUE ");
        }
        sql.append("INDEX IF NOT EXISTS ")
           .append(quoteIdentifier(indexName))
           .append(" ON ")
           .append(quoteIdentifier(tableName))
           .append(" (")
           .append(quoteIdentifier(columnName));
        
        if (order == IndexOrder.DESC) {
            sql.append(" DESC");
        }
        
        sql.append(")");
        return sql.toString();
    }

    @Override
    public String createCompositeIndexStatement(String indexName, String tableName,
                                              String[] columnNames, IndexOrder[] orders, boolean unique) {
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE ");
        if (unique) {
            sql.append("UNIQUE ");
        }
        sql.append("INDEX IF NOT EXISTS ")
           .append(quoteIdentifier(indexName))
           .append(" ON ")
           .append(quoteIdentifier(tableName))
           .append(" (");
        
        for (int i = 0; i < columnNames.length; i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(quoteIdentifier(columnNames[i]));
            
            // Apply ordering if specified
            if (orders.length > i && orders[i] == IndexOrder.DESC) {
                sql.append(" DESC");
            }
        }
        
        sql.append(")");
        return sql.toString();
    }

    @Override
    public String dropIndexStatement(String indexName) {
        return "DROP INDEX IF EXISTS " + quoteIdentifier(indexName);
    }

    @Override
    public String checkIndexExistsStatement(String indexName) {
        return "SELECT 1 FROM sqlite_master WHERE type='index' AND name='" + indexName + "'";
    }
}
