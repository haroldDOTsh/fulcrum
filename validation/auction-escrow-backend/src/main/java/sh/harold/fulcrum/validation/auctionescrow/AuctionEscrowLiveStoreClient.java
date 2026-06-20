package sh.harold.fulcrum.validation.auctionescrow;

import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import io.valkey.UnifiedJedis;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.store.cassandra.CassandraClientHandle;
import sh.harold.fulcrum.data.store.kafka.KafkaClientBundle;
import sh.harold.fulcrum.data.store.postgresql.PostgresClientHandle;
import sh.harold.fulcrum.data.store.valkey.ValkeyClientHandle;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class AuctionEscrowLiveStoreClient implements AutoCloseable {
    public static final String RECORD_TABLE = AuctionEscrowStoreBackedRuntime.RECORD_TABLE;
    public static final String DECISION_TABLE = AuctionEscrowStoreBackedRuntime.DECISION_TABLE;
    public static final String CASSANDRA_PROJECTION_TABLE = AuctionEscrowStoreBackedRuntime.CASSANDRA_PROJECTION_TABLE;
    public static final String IDEMPOTENCY_PREFIX = AuctionEscrowStoreBackedRuntime.IDEMPOTENCY_PREFIX;

    private final Config config;
    private final KafkaClientBundle kafka;
    private final PostgresClientHandle postgres;
    private final CassandraClientHandle cassandra;
    private final ValkeyClientHandle valkey;
    private final List<ResponseObservation> observedResponses = new ArrayList<>();

    private AuctionEscrowLiveStoreClient(
            Config config,
            KafkaClientBundle kafka,
            PostgresClientHandle postgres,
            CassandraClientHandle cassandra,
            ValkeyClientHandle valkey) {
        this.config = Objects.requireNonNull(config, "config");
        this.kafka = Objects.requireNonNull(kafka, "kafka");
        this.postgres = Objects.requireNonNull(postgres, "postgres");
        this.cassandra = Objects.requireNonNull(cassandra, "cassandra");
        this.valkey = Objects.requireNonNull(valkey, "valkey");
    }

    public static AuctionEscrowLiveStoreClient open(Config config) {
        Config checked = Objects.requireNonNull(config, "config");
        KafkaClientBundle kafka = KafkaClientBundle.create(
                checked.kafkaBootstrapServers(),
                checked.clientId(),
                checked.groupId());
        PostgresClientHandle postgres = PostgresClientHandle.create(
                checked.postgresJdbcUrl(),
                checked.postgresUsername(),
                checked.postgresPassword());
        CassandraClientHandle cassandra = CassandraClientHandle.createLazy(
                cassandraContactPoints(checked.cassandraContactPoints()),
                checked.cassandraLocalDatacenter());
        ValkeyClientHandle valkey = valkey(checked.valkeyEndpoint());
        try {
            kafka.subscribe(List.of(checked.responseTopic()));
            return new AuctionEscrowLiveStoreClient(checked, kafka, postgres, cassandra, valkey);
        } catch (RuntimeException exception) {
            closeAll(kafka, cassandra, valkey, postgres);
            throw exception;
        }
    }

    public CommandAppend append(AuthorityCommand<AuctionEscrowCommand> command) {
        AuthorityCommand<AuctionEscrowCommand> checked = Objects.requireNonNull(command, "command");
        try {
            RecordMetadata metadata = kafka.producer().send(new ProducerRecord<>(
                            config.commandTopic(),
                            checked.envelope().aggregateId().value(),
                            AuctionEscrowCommandWireCodec.encode(checked)))
                    .get(config.operationTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return new CommandAppend(
                    checked.envelope().commandId().value(),
                    checked.envelope().idempotencyKey().value(),
                    checked.envelope().aggregateId().value(),
                    metadata.topic(),
                    metadata.partition(),
                    metadata.offset());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while appending escrow command to Kafka", exception);
        } catch (TimeoutException exception) {
            throw new IllegalStateException("timed out appending escrow command to Kafka", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to append escrow command to Kafka", exception);
        }
    }

    public long awaitFencingEpoch(Duration timeout) {
        return await(timeout, "auction escrow fencing epoch", () -> {
            try (Connection connection = postgres.dataSource().getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT COALESCE(MAX(fencing_epoch), 0) FROM " + RECORD_TABLE + ";")) {
                if (!result.next()) {
                    return Optional.empty();
                }
                long epoch = result.getLong(1);
                return epoch > 0 ? Optional.of(epoch) : Optional.empty();
            } catch (SQLException exception) {
                throw new IllegalStateException("failed to read auction escrow fencing epoch", exception);
            }
        });
    }

    public ResponseObservation awaitResponse(String commandId, Duration timeout) {
        String checked = requireNonBlank(commandId, "commandId");
        return await(timeout, "auction escrow response " + checked, () -> {
            Optional<ResponseObservation> existing = observedResponses.stream()
                    .filter(response -> response.commandId().equals(checked))
                    .findFirst();
            if (existing.isPresent()) {
                return existing;
            }
            pollResponses(Duration.ofMillis(200));
            return observedResponses.stream()
                    .filter(response -> response.commandId().equals(checked))
                    .findFirst();
        });
    }

    public List<ResponseObservation> pollResponses(Duration timeout) {
        ConsumerRecords<String, String> records = kafka.consumer().poll(positive(timeout, "timeout"));
        List<ResponseObservation> observations = new ArrayList<>();
        for (ConsumerRecord<String, String> record : records) {
            ResponseObservation observation = ResponseObservation.from(record);
            observedResponses.add(observation);
            observations.add(observation);
        }
        return observations;
    }

    public List<ResponseObservation> observedResponses() {
        return List.copyOf(observedResponses);
    }

    public PostgresDecisionObservation awaitDecision(String commandId, Duration timeout) {
        String checked = requireNonBlank(commandId, "commandId");
        return await(timeout, "auction escrow decision " + checked, () -> decision(checked));
    }

    public PostgresRecordObservation awaitRecord(String aggregateId, long minimumRevision, Duration timeout) {
        String checked = requireNonBlank(aggregateId, "aggregateId");
        if (minimumRevision < 0) {
            throw new IllegalArgumentException("minimumRevision must be non-negative");
        }
        return await(timeout, "auction escrow record " + checked, () -> record(checked)
                .filter(observation -> observation.revision() >= minimumRevision));
    }

    public CassandraProjectionObservation awaitProjection(
            String aggregateId,
            EscrowStatus status,
            long minimumRevision,
            Duration timeout) {
        String checked = requireNonBlank(aggregateId, "aggregateId");
        EscrowStatus checkedStatus = Objects.requireNonNull(status, "status");
        if (minimumRevision < 0) {
            throw new IllegalArgumentException("minimumRevision must be non-negative");
        }
        return await(timeout, "auction escrow Cassandra projection " + checked, () -> projection(checked)
                .filter(observation -> observation.status().equals(checkedStatus.name()))
                .filter(observation -> observation.revision() >= minimumRevision));
    }

    public ValkeyObservation awaitIdempotency(String idempotencyKey, Duration timeout) {
        String key = idempotencyKey(idempotencyKey);
        return await(timeout, "auction escrow idempotency key " + key, () -> {
            String payload = valkey.client().get(key);
            if (payload == null || payload.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new ValkeyObservation(key, payload, sha256(payload)));
        });
    }

    @Override
    public void close() {
        closeAll(kafka, cassandra, valkey, postgres);
    }

    private Optional<PostgresDecisionObservation> decision(String commandId) {
        String sql = """
                SELECT command_id, aggregate_id, source_topic, source_partition, source_offset,
                       status, rejection_reason, revision, replayed, trace_id, decision_payload
                FROM %s
                WHERE command_id = ?
                """.formatted(DECISION_TABLE);
        try (Connection connection = postgres.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, commandId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PostgresDecisionObservation(
                        result.getString("command_id"),
                        result.getString("aggregate_id"),
                        result.getString("source_topic"),
                        result.getInt("source_partition"),
                        result.getLong("source_offset"),
                        result.getString("status"),
                        result.getString("rejection_reason"),
                        result.getLong("revision"),
                        result.getBoolean("replayed"),
                        result.getString("trace_id"),
                        result.getString("decision_payload")));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to read auction escrow decision " + commandId, exception);
        }
    }

    private Optional<PostgresRecordObservation> record(String aggregateId) {
        String sql = """
                SELECT aggregate_id, revision, fencing_epoch, state_payload
                FROM %s
                WHERE aggregate_id = ?
                """.formatted(RECORD_TABLE);
        try (Connection connection = postgres.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, aggregateId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PostgresRecordObservation(
                        result.getString("aggregate_id"),
                        result.getLong("revision"),
                        result.getLong("fencing_epoch"),
                        result.getString("state_payload")));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to read auction escrow record " + aggregateId, exception);
        }
    }

    private Optional<CassandraProjectionObservation> projection(String aggregateId) {
        Row row = cassandra.session().execute(SimpleStatement.newInstance(
                "SELECT aggregate_id, auction_id, status, total_held_minor, total_released_minor, revision "
                        + "FROM " + CASSANDRA_PROJECTION_TABLE + " WHERE aggregate_id = ?",
                aggregateId)).one();
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new CassandraProjectionObservation(
                row.getString("aggregate_id"),
                row.getString("auction_id"),
                row.getString("status"),
                row.getLong("total_held_minor"),
                row.getLong("total_released_minor"),
                row.getLong("revision")));
    }

    private static String idempotencyKey(String idempotencyKey) {
        return IDEMPOTENCY_PREFIX + ":" + requireNonBlank(idempotencyKey, "idempotencyKey");
    }

    private static <T> T await(Duration timeout, String label, Probe<T> probe) {
        Duration checkedTimeout = positive(timeout, "timeout");
        long deadline = System.nanoTime() + checkedTimeout.toNanos();
        RuntimeException lastFailure = null;
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            try {
                Optional<T> value = probe.read();
                if (value.isPresent()) {
                    return value.orElseThrow();
                }
            } catch (RuntimeException exception) {
                lastFailure = exception;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for " + label, exception);
            }
        }
        IllegalStateException timeoutFailure = new IllegalStateException(
                "timed out after " + checkedTimeout + " waiting for " + label);
        if (lastFailure != null) {
            timeoutFailure.addSuppressed(lastFailure);
        }
        throw timeoutFailure;
    }

    private static List<InetSocketAddress> cassandraContactPoints(String contactPoints) {
        List<InetSocketAddress> endpoints = Arrays.stream(requireNonBlank(contactPoints, "contactPoints").split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .map(value -> hostPort("cassandraContactPoints", value))
                .map(endpoint -> InetSocketAddress.createUnresolved(endpoint.host(), endpoint.port()))
                .toList();
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("cassandraContactPoints must contain at least one host:port");
        }
        return endpoints;
    }

    private static ValkeyClientHandle valkey(String endpoint) {
        UnifiedHostPort hostPort = hostPort("valkeyEndpoint", endpoint);
        return ValkeyClientHandle.create(hostPort.host(), hostPort.port());
    }

    private static UnifiedHostPort hostPort(String label, String value) {
        String checked = requireNonBlank(value, label);
        int separator = checked.lastIndexOf(':');
        if (separator <= 0 || separator == checked.length() - 1) {
            throw new IllegalArgumentException(label + " must be host:port");
        }
        int port;
        try {
            port = Integer.parseInt(checked.substring(separator + 1).trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " port must be numeric", exception);
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException(label + " port must be between 1 and 65535");
        }
        return new UnifiedHostPort(checked.substring(0, separator).trim(), port);
    }

    private static Map<String, String> parsePipeFields(String payload) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (payload == null || payload.isBlank()) {
            return fields;
        }
        for (String field : payload.split("\\|", -1)) {
            String[] parts = field.split("=", 2);
            fields.put(parts[0], parts.length == 2 ? parts[1] : "");
        }
        return fields;
    }

    private static Map<String, String> parseLineFields(String payload) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (payload == null || payload.isBlank()) {
            return fields;
        }
        payload.lines()
                .filter(line -> !line.isBlank())
                .map(line -> line.split("=", 2))
                .forEach(parts -> fields.put(parts[0], parts.length == 2 ? parts[1] : ""));
        return fields;
    }

    private static Duration positive(Duration duration, String label) {
        Duration checked = Objects.requireNonNull(duration, label);
        if (checked.isZero() || checked.isNegative()) {
            throw new IllegalArgumentException(label + " must be positive");
        }
        return checked;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    private static void closeAll(AutoCloseable... closeables) {
        RuntimeException failure = null;
        for (AutoCloseable closeable : closeables) {
            try {
                closeable.close();
            } catch (Exception exception) {
                if (failure == null) {
                    failure = new IllegalStateException("failed to close auction escrow live-store client", exception);
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @FunctionalInterface
    private interface Probe<T> {
        Optional<T> read();
    }

    public record Config(
            String kafkaBootstrapServers,
            String commandTopic,
            String responseTopic,
            String clientId,
            String groupId,
            String postgresJdbcUrl,
            String postgresUsername,
            String postgresPassword,
            String cassandraContactPoints,
            String cassandraLocalDatacenter,
            String valkeyEndpoint,
            Duration operationTimeout) {
        public Config {
            kafkaBootstrapServers = requireNonBlank(kafkaBootstrapServers, "kafkaBootstrapServers");
            commandTopic = requireNonBlank(commandTopic, "commandTopic");
            responseTopic = requireNonBlank(responseTopic, "responseTopic");
            clientId = requireNonBlank(clientId, "clientId");
            groupId = requireNonBlank(groupId, "groupId");
            postgresJdbcUrl = requireNonBlank(postgresJdbcUrl, "postgresJdbcUrl");
            postgresUsername = requireNonBlank(postgresUsername, "postgresUsername");
            postgresPassword = requireNonBlank(postgresPassword, "postgresPassword");
            cassandraContactPoints = requireNonBlank(cassandraContactPoints, "cassandraContactPoints");
            cassandraLocalDatacenter = requireNonBlank(cassandraLocalDatacenter, "cassandraLocalDatacenter");
            valkeyEndpoint = requireNonBlank(valkeyEndpoint, "valkeyEndpoint");
            operationTimeout = positive(operationTimeout, "operationTimeout");
        }

        public static Config from(Map<String, String> environment, String clientId, String groupId) {
            Objects.requireNonNull(environment, "environment");
            return new Config(
                    required(environment, "FULCRUM_KAFKA_BOOTSTRAP_SERVERS"),
                    required(environment, "FULCRUM_ESCROW_COMMAND_TOPIC"),
                    required(environment, "FULCRUM_ESCROW_RESPONSE_TOPIC"),
                    clientId,
                    groupId,
                    required(environment, "FULCRUM_POSTGRES_JDBC_URL"),
                    required(environment, "FULCRUM_POSTGRES_USERNAME"),
                    required(environment, "FULCRUM_POSTGRES_PASSWORD"),
                    required(environment, "FULCRUM_CASSANDRA_CONTACT_POINTS"),
                    required(environment, "FULCRUM_CASSANDRA_LOCAL_DATACENTER"),
                    required(environment, "FULCRUM_VALKEY_ENDPOINT"),
                    Duration.ofSeconds(longValue(
                            environment.getOrDefault("FULCRUM_WITNESS_LIVE_TIMEOUT_SECONDS", "30"),
                            "FULCRUM_WITNESS_LIVE_TIMEOUT_SECONDS")));
        }

        private static String required(Map<String, String> environment, String name) {
            return requireNonBlank(environment.get(name), name);
        }

        private static long longValue(String raw, String name) {
            try {
                long value = Long.parseLong(requireNonBlank(raw, name));
                if (value < 1) {
                    throw new IllegalArgumentException(name + " must be positive");
                }
                return value;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(name + " must be a positive long", exception);
            }
        }
    }

    public record CommandAppend(
            String commandId,
            String idempotencyKey,
            String aggregateId,
            String topic,
            int partition,
            long offset) {
        public CommandAppend {
            commandId = requireNonBlank(commandId, "commandId");
            idempotencyKey = requireNonBlank(idempotencyKey, "idempotencyKey");
            aggregateId = requireNonBlank(aggregateId, "aggregateId");
            topic = requireNonBlank(topic, "topic");
            if (partition < 0) {
                throw new IllegalArgumentException("partition must be non-negative");
            }
            if (offset < 0) {
                throw new IllegalArgumentException("offset must be non-negative");
            }
        }
    }

    public record ResponseObservation(
            String commandId,
            String topic,
            int partition,
            long offset,
            String payload,
            Map<String, String> fields) {
        public ResponseObservation {
            commandId = requireNonBlank(commandId, "commandId");
            topic = requireNonBlank(topic, "topic");
            if (partition < 0) {
                throw new IllegalArgumentException("partition must be non-negative");
            }
            if (offset < 0) {
                throw new IllegalArgumentException("offset must be non-negative");
            }
            payload = requireNonBlank(payload, "payload");
            fields = Map.copyOf(Objects.requireNonNull(fields, "fields"));
        }

        static ResponseObservation from(ConsumerRecord<String, String> record) {
            return new ResponseObservation(
                    requireNonBlank(record.key(), "response command key"),
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.value(),
                    parsePipeFields(record.value()));
        }
    }

    public record PostgresDecisionObservation(
            String commandId,
            String aggregateId,
            String sourceTopic,
            int sourcePartition,
            long sourceOffset,
            String status,
            String rejectionReason,
            long revision,
            boolean replayed,
            String traceId,
            String decisionPayload) {
        public PostgresDecisionObservation {
            commandId = requireNonBlank(commandId, "commandId");
            aggregateId = requireNonBlank(aggregateId, "aggregateId");
            sourceTopic = requireNonBlank(sourceTopic, "sourceTopic");
            status = requireNonBlank(status, "status");
            rejectionReason = rejectionReason == null ? "" : rejectionReason;
            traceId = requireNonBlank(traceId, "traceId");
            decisionPayload = requireNonBlank(decisionPayload, "decisionPayload");
        }
    }

    public record PostgresRecordObservation(
            String aggregateId,
            long revision,
            long fencingEpoch,
            String statePayload,
            Map<String, String> stateFields) {
        public PostgresRecordObservation(
                String aggregateId,
                long revision,
                long fencingEpoch,
                String statePayload) {
            this(aggregateId, revision, fencingEpoch, statePayload, parseLineFields(statePayload));
        }

        public PostgresRecordObservation {
            aggregateId = requireNonBlank(aggregateId, "aggregateId");
            if (revision < 0 || fencingEpoch < 0) {
                throw new IllegalArgumentException("revision and fencingEpoch must be non-negative");
            }
            statePayload = requireNonBlank(statePayload, "statePayload");
            stateFields = Map.copyOf(Objects.requireNonNull(stateFields, "stateFields"));
        }
    }

    public record CassandraProjectionObservation(
            String aggregateId,
            String auctionId,
            String status,
            long totalHeldMinor,
            long totalReleasedMinor,
            long revision) {
        public CassandraProjectionObservation {
            aggregateId = requireNonBlank(aggregateId, "aggregateId");
            auctionId = requireNonBlank(auctionId, "auctionId");
            status = requireNonBlank(status, "status");
            if (totalHeldMinor < 0 || totalReleasedMinor < 0 || revision < 0) {
                throw new IllegalArgumentException("projection totals and revision must be non-negative");
            }
        }
    }

    public record ValkeyObservation(String key, String payload, String payloadFingerprint) {
        public ValkeyObservation {
            key = requireNonBlank(key, "key");
            payload = requireNonBlank(payload, "payload");
            payloadFingerprint = requireNonBlank(payloadFingerprint, "payloadFingerprint");
        }
    }

    private record UnifiedHostPort(String host, int port) {
        private UnifiedHostPort {
            host = requireNonBlank(host, "host");
        }
    }
}
