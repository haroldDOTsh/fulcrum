package sh.harold.fulcrum.api.data.backend.sql;

import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.query.QueryFilter;

import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility class for translating query filters into SQL statements.
 */
public class SqlQueryTranslator {
    
    private static final Logger LOGGER = Logger.getLogger(SqlQueryTranslator.class.getName());

    public static String translate(PlayerDataSchema<?> schema, List<QueryFilter> filters, Optional<Integer> limit, Optional<Integer> offset) {
        LOGGER.log(Level.FINE, "Translating query for schema: {0} with {1} filters",
                   new Object[]{schema.schemaKey(), filters.size()});
        
        // Validate that all filters are SQL-compatible before translation
        for (QueryFilter filter : filters) {
            if (!filter.isSqlCompatible()) {
                LOGGER.log(Level.WARNING, "SQL translation failed for schema {0}: Filter ''{1}'' is not SQL-compatible (uses custom predicate)",
                           new Object[]{schema.schemaKey(), filter.toString()});
                LOGGER.log(Level.INFO, "Falling back to in-memory filtering for schema {0}", schema.schemaKey());
                // Return null to indicate filters cannot be translated to SQL
                return null;
            }
        }

        StringBuilder sqlBuilder = new StringBuilder("SELECT * FROM ");
        sqlBuilder.append(schema.schemaKey());

        if (!filters.isEmpty()) {
            sqlBuilder.append(" WHERE ");
            for (int i = 0; i < filters.size(); i++) {
                QueryFilter filter = filters.get(i);
                sqlBuilder.append(filter.toSql());
                if (i < filters.size() - 1) {
                    sqlBuilder.append(" AND ");
                }
            }
        }

        limit.ifPresent(l -> sqlBuilder.append(" LIMIT ").append(l));
        offset.ifPresent(o -> sqlBuilder.append(" OFFSET ").append(o));

        String sql = sqlBuilder.toString();
        LOGGER.log(Level.FINE, "Generated SQL for schema {0}: {1}", new Object[]{schema.schemaKey(), sql});
        return sql;
    }
}