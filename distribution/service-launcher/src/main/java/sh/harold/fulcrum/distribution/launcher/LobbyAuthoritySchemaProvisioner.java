package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.data.store.cassandra.CassandraClientHandle;
import sh.harold.fulcrum.data.store.postgresql.PostgresClientHandle;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class LobbyAuthoritySchemaProvisioner {
    static final String POSTGRES_MIGRATION_RESOURCE = "/fulcrum/migrations/lobby-authority/postgres-authority.sql";
    static final String CASSANDRA_MIGRATION_RESOURCE = "/fulcrum/migrations/lobby-authority/cassandra-authority.cql";

    private LobbyAuthoritySchemaProvisioner() {
    }

    public static void main(String[] args) {
        if (args.length > 0) {
            throw new IllegalArgumentException("LobbyAuthoritySchemaProvisioner reads configuration from environment");
        }
        Config config = Config.fromEnvironment(RuntimeEnvironment.system());
        try (StoreSchemaExecutor executor = StoreSchemaExecutor.connect(config)) {
            Result result = provision(config, executor);
            System.out.println("Applied lobby authority schema migrations: postgresStatements="
                    + result.postgresStatementCount()
                    + ", cassandraStatements=" + result.cassandraStatementCount());
        }
    }

    static Result provision(Config config, SchemaExecutor executor) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(executor, "executor");
        List<String> postgresStatements = statements(loadResource(POSTGRES_MIGRATION_RESOURCE));
        List<String> cassandraStatements = statements(loadResource(CASSANDRA_MIGRATION_RESOURCE));
        postgresStatements.forEach(executor::executePostgres);
        cassandraStatements.forEach(executor::executeCassandra);
        return new Result(postgresStatements.size(), cassandraStatements.size());
    }

    private static String loadResource(String resourceName) {
        try (InputStream stream = LobbyAuthoritySchemaProvisioner.class.getResourceAsStream(resourceName)) {
            if (stream == null) {
                throw new IllegalStateException("Missing lobby authority schema migration resource " + resourceName);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read lobby authority schema migration resource " + resourceName, exception);
        }
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

    interface SchemaExecutor {
        void executePostgres(String statement);

        void executeCassandra(String statement);
    }

    record Result(int postgresStatementCount, int cassandraStatementCount) {
        Result {
            if (postgresStatementCount < 0 || cassandraStatementCount < 0) {
                throw new IllegalArgumentException("statement counts must not be negative");
            }
        }
    }

    record Config(
            RuntimeConnectionSettings.PostgresJdbcSettings postgres,
            List<RuntimeConnectionSettings.HostPort> cassandraContactPoints,
            String cassandraLocalDatacenter) {
        Config {
            postgres = Objects.requireNonNull(postgres, "postgres");
            cassandraContactPoints = List.copyOf(cassandraContactPoints);
            if (cassandraContactPoints.isEmpty()) {
                throw new IllegalArgumentException("cassandraContactPoints must not be empty");
            }
            cassandraLocalDatacenter = requireNonBlank(cassandraLocalDatacenter, "cassandraLocalDatacenter");
        }

        static Config fromEnvironment(RuntimeEnvironment environment) {
            Objects.requireNonNull(environment, "environment");
            return new Config(
                    new RuntimeConnectionSettings.PostgresJdbcSettings(
                            required(environment, "FULCRUM_POSTGRES_JDBC_URL"),
                            required(environment, "FULCRUM_POSTGRES_USERNAME"),
                            required(environment, "FULCRUM_POSTGRES_PASSWORD")),
                    hostPorts("FULCRUM_CASSANDRA_CONTACT_POINTS", required(environment, "FULCRUM_CASSANDRA_CONTACT_POINTS")),
                    environment.value("FULCRUM_CASSANDRA_LOCAL_DATACENTER").orElse("datacenter1"));
        }
    }

    static final class StoreSchemaExecutor implements SchemaExecutor, AutoCloseable {
        private final PostgresClientHandle postgres;
        private final CassandraClientHandle cassandra;

        private StoreSchemaExecutor(PostgresClientHandle postgres, CassandraClientHandle cassandra) {
            this.postgres = Objects.requireNonNull(postgres, "postgres");
            this.cassandra = Objects.requireNonNull(cassandra, "cassandra");
        }

        static StoreSchemaExecutor connect(Config config) {
            Objects.requireNonNull(config, "config");
            return new StoreSchemaExecutor(
                    PostgresClientHandle.create(
                            config.postgres().jdbcUrl(),
                            config.postgres().username(),
                            config.postgres().password()),
                    CassandraClientHandle.createLazy(
                            config.cassandraContactPoints().stream()
                                    .map(StoreSchemaExecutor::socketAddress)
                                    .toList(),
                            config.cassandraLocalDatacenter()));
        }

        @Override
        public void executePostgres(String statement) {
            try (Connection connection = postgres.dataSource().getConnection();
                 Statement jdbcStatement = connection.createStatement()) {
                jdbcStatement.execute(statement);
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to apply PostgreSQL authority schema migration statement", exception);
            }
        }

        @Override
        public void executeCassandra(String statement) {
            cassandra.session().execute(statement);
        }

        @Override
        public void close() {
            RuntimeException failure = null;
            try {
                cassandra.close();
            } catch (RuntimeException exception) {
                failure = exception;
            }
            try {
                postgres.close();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private static InetSocketAddress socketAddress(RuntimeConnectionSettings.HostPort endpoint) {
            return InetSocketAddress.createUnresolved(endpoint.host(), endpoint.port());
        }
    }

    private static List<RuntimeConnectionSettings.HostPort> hostPorts(String label, String value) {
        return Arrays.stream(requireNonBlank(value, label).split(","))
                .map(String::strip)
                .filter(endpoint -> !endpoint.isEmpty())
                .map(endpoint -> hostPort(label, endpoint))
                .toList();
    }

    private static RuntimeConnectionSettings.HostPort hostPort(String label, String value) {
        int separator = value.lastIndexOf(':');
        if (separator < 1 || separator == value.length() - 1) {
            throw new RuntimeConfigurationException(label + " must contain host:port entries");
        }
        String host = value.substring(0, separator).trim();
        int port;
        try {
            port = Integer.parseInt(value.substring(separator + 1).trim());
        } catch (NumberFormatException exception) {
            throw new RuntimeConfigurationException(label + " port must be numeric");
        }
        return new RuntimeConnectionSettings.HostPort(host, port);
    }

    private static String required(RuntimeEnvironment environment, String name) {
        return environment.value(name)
                .orElseThrow(() -> new RuntimeConfigurationException("Missing required environment binding " + name));
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
