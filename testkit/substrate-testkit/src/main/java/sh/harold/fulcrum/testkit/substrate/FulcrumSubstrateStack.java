package sh.harold.fulcrum.testkit.substrate;

import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class FulcrumSubstrateStack implements AutoCloseable {
    private static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("apache/kafka-native:4.3.0");
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18.4");
    private static final DockerImageName CASSANDRA_IMAGE = DockerImageName.parse("cassandra:5.0.8");
    private static final DockerImageName VALKEY_IMAGE = DockerImageName.parse("valkey/valkey:9.1.0");
    private static final Duration CONTAINER_STOP_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration CONTAINER_EXEC_TIMEOUT = Duration.ofSeconds(60);

    private final KafkaContainer kafka = new KafkaContainer(KAFKA_IMAGE);
    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withStartupTimeout(Duration.ofMinutes(3));
    private final CassandraContainer cassandra = new CassandraContainer(CASSANDRA_IMAGE)
            .withStartupTimeout(Duration.ofMinutes(5));
    private final GenericContainer<?> valkey = new GenericContainer<>(VALKEY_IMAGE)
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofMinutes(2));

    public static FulcrumSubstrateStack create() {
        return new FulcrumSubstrateStack();
    }

    public FulcrumSubstrateStack start() {
        Startables.deepStart(kafka, postgres, cassandra, valkey).join();
        return this;
    }

    public boolean kafkaRunning() {
        return kafka.isRunning();
    }

    public boolean postgresRunning() {
        return postgres.isRunning();
    }

    public boolean cassandraRunning() {
        return cassandra.isRunning();
    }

    public boolean valkeyRunning() {
        return valkey.isRunning();
    }

    public String kafkaBootstrapServers() {
        return kafka.getBootstrapServers();
    }

    public String postgresJdbcUrl() {
        return postgres.getJdbcUrl();
    }

    public String postgresUsername() {
        return postgres.getUsername();
    }

    public String postgresPassword() {
        return postgres.getPassword();
    }

    public String cassandraContactPoint() {
        return cassandra.getContactPoint().toString();
    }

    public String cassandraHost() {
        return cassandra.getHost();
    }

    public int cassandraPort() {
        return cassandra.getMappedPort(9042);
    }

    public String valkeyEndpoint() {
        return valkey.getHost() + ":" + valkey.getMappedPort(6379);
    }

    public String valkeyHost() {
        return valkey.getHost();
    }

    public int valkeyPort() {
        return valkey.getMappedPort(6379);
    }

    public boolean postgresAcceptsConnections() {
        Container.ExecResult result = exec(postgres, "pg_isready", "-U", postgres.getUsername(), "-d", postgres.getDatabaseName());
        return result.getExitCode() == 0;
    }

    public boolean cassandraReportsReady() {
        Container.ExecResult result = exec(cassandra, "nodetool", "status");
        return result.getExitCode() == 0;
    }

    public boolean valkeyRespondsToPing() {
        Container.ExecResult result = exec(valkey, "valkey-cli", "PING");
        return result.getExitCode() == 0 && result.getStdout().contains("PONG");
    }

    public void executePostgres(String sql) {
        Container.ExecResult result = exec(postgres,
                "psql",
                "-v",
                "ON_ERROR_STOP=1",
                "-U",
                postgres.getUsername(),
                "-d",
                postgres.getDatabaseName(),
                "-c",
                sql);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("PostgreSQL command failed: " + result.getStderr());
        }
    }

    public String queryPostgresScalar(String sql) {
        Container.ExecResult result = exec(postgres,
                "psql",
                "-t",
                "-A",
                "-v",
                "ON_ERROR_STOP=1",
                "-U",
                postgres.getUsername(),
                "-d",
                postgres.getDatabaseName(),
                "-c",
                sql);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("PostgreSQL query failed: " + result.getStderr());
        }
        return result.getStdout().strip();
    }

    public void executeCassandra(String cql) {
        Container.ExecResult result = exec(cassandra,
                "cqlsh",
                "--connect-timeout=10",
                "--request-timeout=10",
                "-e",
                cql.strip());
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("Cassandra command failed: " + result.getStderr());
        }
    }

    public String queryCassandra(String cql) {
        Container.ExecResult result = exec(cassandra,
                "cqlsh",
                "--connect-timeout=10",
                "--request-timeout=10",
                "-e",
                cql.strip());
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("Cassandra query failed: " + result.getStderr());
        }
        return result.getStdout().strip();
    }

    public void setValkey(String key, String value) {
        Container.ExecResult result = exec(valkey, "valkey-cli", "SET", key, value);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("Valkey SET failed: " + result.getStderr());
        }
    }

    public String getValkey(String key) {
        Container.ExecResult result = exec(valkey, "valkey-cli", "GET", key);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("Valkey GET failed: " + result.getStderr());
        }
        return result.getStdout().strip();
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (GenericContainer<?> container : List.of(valkey, cassandra, postgres, kafka)) {
            try {
                stopContainer(container);
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static void stopContainer(GenericContainer<?> container) {
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "fulcrum-substrate-testkit-container-stop");
            thread.setDaemon(true);
            return thread;
        });
        try {
            executor.submit(container::stop).get(CONTAINER_STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            throw new IllegalStateException(
                    "Timed out stopping substrate test container " + container.getDockerImageName(),
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping substrate test container", exception);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed to stop substrate test container " + container.getDockerImageName(),
                    exception);
        } finally {
            executor.shutdownNow();
        }
    }

    private static Container.ExecResult exec(GenericContainer<?> container, String... command) {
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "fulcrum-substrate-testkit-container-exec");
            thread.setDaemon(true);
            return thread;
        });
        try {
            return executor.submit(() -> {
                try {
                    return container.execInContainer(command);
                } catch (IOException exception) {
                    throw new IllegalStateException("Container command failed", exception);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Container command interrupted", exception);
                }
            }).get(CONTAINER_EXEC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            throw new IllegalStateException(
                    "Timed out running command in substrate test container "
                            + container.getDockerImageName()
                            + ": "
                            + String.join(" ", command),
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Container command interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Container command failed", cause);
        } finally {
            executor.shutdownNow();
        }
    }
}
