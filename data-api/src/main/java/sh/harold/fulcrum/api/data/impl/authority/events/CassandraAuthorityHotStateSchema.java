package sh.harold.fulcrum.api.data.impl.authority.events;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Applies the canonical Cassandra hot-state projection schema.
 */
public final class CassandraAuthorityHotStateSchema {
    private CassandraAuthorityHotStateSchema() {
    }

    public static List<SimpleStatement> statements(String keyspace) {
        String effectiveKeyspace = requireIdentifier(keyspace, "keyspace");
        String cql = resource(CassandraAuthorityHotStateProjection.SCHEMA_RESOURCE);
        String qualified = qualifyTables(stripLineComments(cql), effectiveKeyspace);
        List<SimpleStatement> statements = new ArrayList<>();
        for (String rawStatement : qualified.split(";")) {
            String statement = rawStatement.trim();
            if (!statement.isBlank()) {
                statements.add(SimpleStatement.newInstance(statement));
            }
        }
        return List.copyOf(statements);
    }

    public static void apply(CqlSession session, String keyspace) {
        Objects.requireNonNull(session, "session");
        statements(keyspace).forEach(session::execute);
    }

    private static String qualifyTables(String cql, String keyspace) {
        String result = cql;
        String tableDeclarationPrefix = String.join(" ", "CREATE", "TABLE", "IF", "NOT", "EXISTS") + " ";
        for (CassandraAuthorityHotStateProjection.CassandraProjectionTable table : declaredTables()) {
            result = result.replace(
                tableDeclarationPrefix + table.table(),
                tableDeclarationPrefix + keyspace + "." + table.table()
            );
        }
        return result;
    }

    private static List<CassandraAuthorityHotStateProjection.CassandraProjectionTable> declaredTables() {
        return List.of(
            new CassandraAuthorityHotStateProjection.CassandraProjectionTable(
                CassandraAuthorityHotStateProjection.PROFILE_TABLE,
                "player_id"
            ),
            new CassandraAuthorityHotStateProjection.CassandraProjectionTable(
                CassandraAuthorityHotStateProjection.PRESENCE_TABLE,
                "subject_id"
            ),
            new CassandraAuthorityHotStateProjection.CassandraProjectionTable(
                CassandraAuthorityHotStateProjection.RANK_TABLE,
                "player_id"
            ),
            new CassandraAuthorityHotStateProjection.CassandraProjectionTable(
                CassandraAuthorityHotStateProjection.MATCH_TABLE,
                "match_id"
            )
        );
    }

    private static String stripLineComments(String cql) {
        StringBuilder stripped = new StringBuilder();
        for (String line : cql.split("\\R")) {
            String trimmed = line.stripLeading();
            if (!trimmed.startsWith("--")) {
                stripped.append(line).append('\n');
            }
        }
        return stripped.toString();
    }

    private static String resource(String path) {
        ClassLoader classLoader = CassandraAuthorityHotStateSchema.class.getClassLoader();
        try (InputStream input = classLoader.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Missing Cassandra schema resource " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read Cassandra schema resource " + path, exception);
        }
    }

    private static String requireIdentifier(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String trimmed = value.trim();
        if (!trimmed.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(field + " must be a Cassandra identifier");
        }
        return trimmed;
    }
}
