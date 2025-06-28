package sh.harold.fulcrum.api.data.backend.sql;

import java.util.List;
import java.util.UUID;

/**
 * PostgreSQL dialect implementation for Fulcrum Data API.
 * Provides type mapping, identifier quoting, and upsert logic for PostgreSQL.
 */
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
}
