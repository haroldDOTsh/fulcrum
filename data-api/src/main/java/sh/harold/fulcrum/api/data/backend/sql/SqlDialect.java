package sh.harold.fulcrum.api.data.backend.sql;

import java.util.List;

/**
 * Abstraction for SQL dialect-specific type mapping, identifier quoting, and upsert statement generation.
 * Implementations must provide correct mappings for their target database.
 */
public interface SqlDialect {
    /**
     * Maps a Java type to the corresponding SQL type for this dialect.
     *
     * @param javaType Java class (e.g., UUID.class, String.class)
     * @return SQL type string (e.g., "TEXT", "INTEGER", "BOOLEAN")
     */
    String getSqlType(Class<?> javaType);

    /**
     * Quotes a SQL identifier (table or column name) for this dialect.
     *
     * @param identifier Unquoted identifier
     * @return Quoted identifier (e.g., `column` or "column")
     */
    String quoteIdentifier(String identifier);

    /**
     * Generates an upsert statement for this dialect, if supported.
     *
     * @param table        Table name
     * @param columns      List of columns
     * @param conflictKeys List of columns to resolve conflicts on
     * @return Upsert SQL statement
     * @throws UnsupportedOperationException if not supported
     */
    default String getUpsertStatement(String table, List<String> columns, List<String> conflictKeys) {
        throw new UnsupportedOperationException("Dialect does not support upsert yet");
    }
}
