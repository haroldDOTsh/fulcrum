package sh.harold.fulcrum.api.data.backend.sql;

import sh.harold.fulcrum.api.data.annotation.IndexOrder;
import java.util.List;
import java.util.UUID;

public final class PostgresDialect implements SqlDialect {
    @Override
    public String getSqlType(Class<?> javaType) {
        if (javaType == UUID.class) return "UUID";
        if (javaType == String.class) return "TEXT";
        if (javaType == int.class || javaType == Integer.class) return "INTEGER";
        if (javaType == long.class || javaType == Long.class) return "BIGINT";
        if (javaType == boolean.class || javaType == Boolean.class) return "BOOLEAN";
        if (javaType == double.class || javaType == Double.class) return "DOUBLE PRECISION";
        if (javaType == float.class || javaType == Float.class) return "REAL";

        // Handle enum types - store as VARCHAR with reasonable length
        if (javaType.isEnum()) {
            return "VARCHAR(64)";
        }

        throw new IllegalArgumentException("Unsupported Java type: " + javaType);
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    @Override
    public String getUpsertStatement(String table, List<String> columns, List<String> conflictKeys) {
        // PostgreSQL: INSERT ... ON CONFLICT (key) DO UPDATE SET ...
        String cols = String.join(", ", columns.stream().map(this::quoteIdentifier).toList());
        String vals = String.join(", ", columns.stream().map(c -> "?").toList());
        String conflict = String.join(", ", conflictKeys.stream().map(this::quoteIdentifier).toList());
        String updates = String.join(", ", columns.stream().filter(c -> !conflictKeys.contains(c)).map(c -> quoteIdentifier(c) + " = EXCLUDED." + quoteIdentifier(c)).toList());
        return "INSERT INTO " + quoteIdentifier(table) + " (" + cols + ") VALUES (" + vals + ") ON CONFLICT (" + conflict + ") DO UPDATE SET " + updates;
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
        return "SELECT 1 FROM pg_indexes WHERE indexname = '" + indexName + "'";
    }
}
