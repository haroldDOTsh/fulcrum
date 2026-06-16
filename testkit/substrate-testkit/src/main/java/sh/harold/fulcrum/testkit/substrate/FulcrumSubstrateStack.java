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
import java.util.stream.Stream;

public final class FulcrumSubstrateStack implements AutoCloseable {
    private static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("apache/kafka-native:4.3.0");
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18.4");
    private static final DockerImageName CASSANDRA_IMAGE = DockerImageName.parse("cassandra:5.0.8");
    private static final DockerImageName VALKEY_IMAGE = DockerImageName.parse("valkey/valkey:9.1.0");

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

    public String cassandraContactPoint() {
        return cassandra.getContactPoint().toString();
    }

    public String valkeyEndpoint() {
        return valkey.getHost() + ":" + valkey.getMappedPort(6379);
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

    @Override
    public void close() {
        Stream.of(valkey, cassandra, postgres, kafka).parallel().forEach(GenericContainer::stop);
    }

    private static Container.ExecResult exec(GenericContainer<?> container, String... command) {
        try {
            return container.execInContainer(command);
        } catch (IOException exception) {
            throw new IllegalStateException("Container command failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Container command interrupted", exception);
        }
    }
}
