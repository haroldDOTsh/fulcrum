package sh.harold.fulcrum.api.data.backend.sql;

import java.util.List;
import java.util.UUID;

/**
 * SQLite dialect implementation for Fulcrum Data API.
 * Provides type mapping, identifier quoting, and upsert logic for SQLite.
 */
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
        throw new IllegalArgumentException("Unsupported Java type: " + javaType);
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    @Override
    public String getUpsertStatement(String table, List<String> columns, List<String> conflictKeys) {
        // SQLite: INSERT OR REPLACE INTO ...
        String cols = String.join(", ", columns.stream().map(this::quoteIdentifier).toList());
        String vals = String.join(", ", columns.stream().map(c -> "?").toList());
        return "INSERT OR REPLACE INTO " + quoteIdentifier(table) + " (" + cols + ") VALUES (" + vals + ")";
    }
}
