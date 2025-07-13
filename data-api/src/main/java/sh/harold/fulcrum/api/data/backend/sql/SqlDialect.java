package sh.harold.fulcrum.api.data.backend.sql;

import sh.harold.fulcrum.api.data.annotation.IndexOrder;
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

    /**
     * Generates a CREATE INDEX statement for a single-column index.
     *
     * @param indexName  Name of the index
     * @param tableName  Name of the table
     * @param columnName Name of the column
     * @param order      Sort order (ASC/DESC)
     * @param unique     Whether this is a unique index
     * @return CREATE INDEX SQL statement
     */
    String createIndexStatement(String indexName, String tableName, String columnName,
                               IndexOrder order, boolean unique);

    /**
     * Generates a CREATE INDEX statement for a composite (multi-column) index.
     *
     * @param indexName   Name of the index
     * @param tableName   Name of the table
     * @param columnNames Array of column names in order
     * @param orders      Array of sort orders corresponding to each column
     * @param unique      Whether this is a unique index
     * @return CREATE INDEX SQL statement
     */
    String createCompositeIndexStatement(String indexName, String tableName,
                                       String[] columnNames, IndexOrder[] orders, boolean unique);

    /**
     * Generates a DROP INDEX statement for this dialect.
     *
     * @param indexName Name of the index to drop
     * @return DROP INDEX SQL statement
     */
    String dropIndexStatement(String indexName);

    /**
     * Generates a SQL statement to check if an index exists.
     * Used for safe index creation and migrations.
     *
     * @param indexName Name of the index to check
     * @return SQL query that returns a result if the index exists
     */
    String checkIndexExistsStatement(String indexName);
}
