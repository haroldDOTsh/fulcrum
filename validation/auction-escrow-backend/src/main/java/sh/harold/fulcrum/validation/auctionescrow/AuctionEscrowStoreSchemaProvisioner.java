package sh.harold.fulcrum.validation.auctionescrow;

import sh.harold.fulcrum.data.store.cassandra.CassandraClientHandle;
import sh.harold.fulcrum.data.store.postgresql.PostgresClientHandle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

final class AuctionEscrowStoreSchemaProvisioner {
    private static final String POSTGRES_MIGRATION_RESOURCE =
            "/fulcrum/migrations/auction-escrow/postgres-authority.sql";
    private static final String CASSANDRA_MIGRATION_RESOURCE =
            "/fulcrum/migrations/auction-escrow/cassandra-authority.cql";

    private AuctionEscrowStoreSchemaProvisioner() {
    }

    static Result provision(PostgresClientHandle postgres, CassandraClientHandle cassandra) {
        Objects.requireNonNull(postgres, "postgres");
        Objects.requireNonNull(cassandra, "cassandra");
        List<String> postgresStatements = statements(loadResource(POSTGRES_MIGRATION_RESOURCE));
        List<String> cassandraStatements = statements(loadResource(CASSANDRA_MIGRATION_RESOURCE));
        postgresStatements.forEach(statement -> executePostgres(postgres, statement));
        cassandraStatements.forEach(statement -> cassandra.session().execute(statement));
        return new Result(postgresStatements.size(), cassandraStatements.size());
    }

    static List<String> statements(String script) {
        String withoutLineComments = Objects.requireNonNull(script, "script")
                .lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .filter(line -> !line.startsWith("--"))
                .reduce("", (left, right) -> left + right + "\n");
        return Arrays.stream(withoutLineComments.split(";"))
                .map(String::strip)
                .filter(statement -> !statement.isEmpty())
                .toList();
    }

    private static void executePostgres(PostgresClientHandle postgres, String statement) {
        try (Connection connection = postgres.dataSource().getConnection();
             Statement jdbcStatement = connection.createStatement()) {
            jdbcStatement.execute(statement);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to apply auction escrow PostgreSQL schema migration", exception);
        }
    }

    private static String loadResource(String resourceName) {
        try (InputStream stream = AuctionEscrowStoreSchemaProvisioner.class.getResourceAsStream(resourceName)) {
            if (stream == null) {
                throw new IllegalStateException("Missing auction escrow schema migration resource " + resourceName);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read auction escrow schema migration resource " + resourceName, exception);
        }
    }

    record Result(int postgresStatementCount, int cassandraStatementCount) {
        Result {
            if (postgresStatementCount < 0 || cassandraStatementCount < 0) {
                throw new IllegalArgumentException("statement counts must be non-negative");
            }
        }
    }
}
