package sh.harold.fulcrum.validation.storeadapter;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import io.valkey.HostAndPort;
import io.valkey.UnifiedJedis;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import sh.harold.fulcrum.adapters.objectstorage.LocalObjectStorageAdapter;
import sh.harold.fulcrum.adapters.objectstorage.ObjectStorageAdapter;
import sh.harold.fulcrum.adapters.objectstorage.S3ObjectStorageAdapter;
import sh.harold.fulcrum.adapters.objectstorage.StoredObject;
import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.core.artifact.ArtifactBlobLayout;
import sh.harold.fulcrum.core.manifest.ArtifactPin;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityCommandProcessor;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.AuthorityEmission;
import sh.harold.fulcrum.data.authority.AuthorityEmissionKind;
import sh.harold.fulcrum.data.authority.AuthorityMutationResult;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.AuthorityRejectionReason;
import sh.harold.fulcrum.data.authority.StoredAuthorityDecision;
import sh.harold.fulcrum.data.authority.runtime.AuthorityDomainHandler;
import sh.harold.fulcrum.data.authority.runtime.AuthorityEmissionSinks;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRuntimeReceipt;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRuntimeWorker;
import sh.harold.fulcrum.data.store.cassandra.CassandraAuthorityProjectionWriter;
import sh.harold.fulcrum.data.store.kafka.KafkaAuthorityCommandSource;
import sh.harold.fulcrum.data.store.kafka.KafkaAuthorityEmissionSink;
import sh.harold.fulcrum.data.store.kafka.KafkaAuthorityEmissionTopics;
import sh.harold.fulcrum.data.store.kafka.KafkaAuthorityOffsetCommitter;
import sh.harold.fulcrum.data.store.postgresql.JdbcAuthorityDecisionRecorder;
import sh.harold.fulcrum.data.store.postgresql.JdbcAuthorityDecisionRecorderConfig;
import sh.harold.fulcrum.data.store.postgresql.JdbcAuthorityRecordStore;
import sh.harold.fulcrum.data.store.postgresql.JdbcAuthorityRecordStoreConfig;
import sh.harold.fulcrum.data.store.valkey.ValkeyAuthorityCacheSink;
import sh.harold.fulcrum.data.store.valkey.ValkeyIdempotencyLedger;
import sh.harold.fulcrum.data.store.valkey.ValkeyStoredAuthorityDecisionCodec;
import sh.harold.fulcrum.testkit.substrate.FulcrumSubstrateStack;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StoreAdapterCertificationMatrixTest {
    private static final String COMMAND_TOPIC = "cmd.store-adapter-certification";
    private static final String EVENT_TOPIC = "evt.store-adapter-certification";
    private static final String STATE_TOPIC = "state.store-adapter-certification";
    private static final String RESPONSE_TOPIC = "rsp.store-adapter-certification";
    private static final String GROUP_ID = "store-adapter-certification-authority";
    private static final AggregateId AGGREGATE_ID = new AggregateId("cert:aggregate:1");
    private static final PrincipalId PRINCIPAL = new PrincipalId("principal-store-certification");
    private static final long FENCING_EPOCH = 71;
    private static final Instant NOW = Instant.parse("2026-06-16T00:00:00Z");
    private static final DockerImageName MINIO_IMAGE =
            DockerImageName.parse("quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z");
    private static final String OBJECT_BUCKET = "artifact-store";
    private static final String OBJECT_ACCESS_KEY = "fulcrum-object-access";
    private static final String OBJECT_SECRET_KEY = "fulcrum-object-secret";

    @Test
    void realAdaptersPassExecutableCertificationAgainstSubstrateEngines() throws Exception {
        try (FulcrumSubstrateStack stack = FulcrumSubstrateStack.create().start()) {
            createTopics(stack.kafkaBootstrapServers());
            createPostgresSchema(stack);
            createCassandraSchema(stack);

            sendCommand(stack.kafkaBootstrapServers(), commandPayload(
                    "command-cert-1",
                    "idempotency-cert-1",
                    3,
                    0,
                    "cert:aggregate:1:3"));

            assertRedeliversBeforeOffsetCommit(stack.kafkaBootstrapServers());

            PGSimpleDataSource dataSource = dataSource(stack);
            try (KafkaConsumer<String, String> consumer = consumer(stack.kafkaBootstrapServers(), GROUP_ID);
                 KafkaProducer<String, String> producer = producer(stack.kafkaBootstrapServers());
                 CqlSession cqlSession = cassandraSession(stack);
                 UnifiedJedis valkey = new UnifiedJedis(new HostAndPort(stack.valkeyHost(), stack.valkeyPort()))) {
                consumer.subscribe(List.of(COMMAND_TOPIC));
                ValkeyIdempotencyLedger<CertState, CertReceipt> idempotencyLedger = new ValkeyIdempotencyLedger<>(
                        valkey,
                        "cert:idempotency",
                        new CertStoredDecisionCodec(),
                        Optional.of(Duration.ofMinutes(5)));
                AuthorityRuntimeWorker<CertState, CertCommand, CertReceipt> worker = worker(
                        consumer,
                        producer,
                        cqlSession,
                        valkey,
                        dataSource,
                        idempotencyLedger);

                AuthorityRuntimeReceipt accepted = worker.handleNext().orElseThrow();
                assertEquals(AuthorityDecisionStatus.ACCEPTED, accepted.status());
                assertEquals(new Revision(1), accepted.revision());
                assertTrue(!accepted.replayed());

                sendCommand(stack.kafkaBootstrapServers(), commandPayload(
                        "command-cert-1",
                        "idempotency-cert-1",
                        3,
                        0,
                        "cert:aggregate:1:3"));
                AuthorityRuntimeReceipt duplicate = worker.handleNext().orElseThrow();
                assertEquals(AuthorityDecisionStatus.ACCEPTED, duplicate.status());
                assertTrue(duplicate.replayed());

                sendCommand(stack.kafkaBootstrapServers(), commandPayload(
                        "command-cert-conflict",
                        "idempotency-cert-1",
                        5,
                        1,
                        "cert:aggregate:1:5"));
                AuthorityRuntimeReceipt conflict = worker.handleNext().orElseThrow();
                assertEquals(AuthorityDecisionStatus.REJECTED, conflict.status());
                assertTrue(!conflict.replayed());

                StoredAuthorityDecision<CertState, CertReceipt> stored = idempotencyLedger
                        .find(new IdempotencyKey("idempotency-cert-1"))
                        .orElseThrow();
                assertEquals("cert:aggregate:1:3", stored.payloadFingerprint());
                assertEquals(new Revision(1), stored.decision().revision());
                long idempotencyTtlSeconds = valkey.ttl(idempotencyLedger.key(new IdempotencyKey("idempotency-cert-1")));
                assertTrue(idempotencyTtlSeconds > 0 && idempotencyTtlSeconds <= 300);
            }

            assertEquals("1", stack.queryPostgresScalar(
                    "SELECT revision FROM cert_authority_records WHERE aggregate_id = 'cert:aggregate:1';"));
            assertEquals("3", stack.queryPostgresScalar(
                    "SELECT state_payload FROM cert_authority_records WHERE aggregate_id = 'cert:aggregate:1';"));
            assertEquals("1", stack.queryPostgresScalar(
                    "SELECT COUNT(*) FROM cert_authority_decisions WHERE command_id = 'command-cert-1';"));
            assertEquals("REJECTED", stack.queryPostgresScalar(
                    "SELECT status FROM cert_authority_decisions WHERE command_id = 'command-cert-conflict';"));
            assertEquals("IDEMPOTENCY_CONFLICT", stack.queryPostgresScalar(
                    "SELECT rejection_reason FROM cert_authority_decisions WHERE command_id = 'command-cert-conflict';"));
            assertEquals("cert:aggregate:1|3|1", cassandraProjection(stack));
            assertEquals("total=3", stack.getValkey("cert:aggregate:1"));
            assertEquals(List.of("accepted:3"), drainTopic(stack.kafkaBootstrapServers(), EVENT_TOPIC, 1));
            assertEquals(List.of("total=3"), drainTopic(stack.kafkaBootstrapServers(), STATE_TOPIC, 1));
            assertEquals(List.of("status=ACCEPTED;revision=1"), drainTopic(stack.kafkaBootstrapServers(), RESPONSE_TOPIC, 1));
        }
    }

    @Test
    void localObjectStorageAdapterRoundTripsArtifactsAndRealmSnapshots(@TempDir Path root) throws IOException {
        LocalObjectStorageAdapter adapter = new LocalObjectStorageAdapter(root, OBJECT_BUCKET);
        assertObjectStorageRoundTrip(adapter);
    }

    @Test
    void s3ObjectStorageAdapterRoundTripsArtifactsAndRealmSnapshotsAgainstMinio() throws IOException {
        try (GenericContainer<?> minio = new GenericContainer<>(MINIO_IMAGE)
                .withExposedPorts(9000)
                .withEnv("MINIO_ROOT_USER", OBJECT_ACCESS_KEY)
                .withEnv("MINIO_ROOT_PASSWORD", OBJECT_SECRET_KEY)
                .withCommand("server", "/data", "--address", ":9000")
                .waitingFor(Wait.forHttp("/minio/health/ready")
                        .forPort(9000)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(2)))) {
            minio.start();
            URI endpoint = URI.create("http://" + minio.getHost() + ":" + minio.getMappedPort(9000));
            S3ObjectStorageAdapter adapter = new S3ObjectStorageAdapter(
                    endpoint,
                    "us-east-1",
                    OBJECT_ACCESS_KEY,
                    OBJECT_SECRET_KEY,
                    OBJECT_BUCKET);
            assertObjectStorageRoundTrip(adapter);
        }
    }

    private static void assertObjectStorageRoundTrip(ObjectStorageAdapter adapter) throws IOException {
        byte[] artifactBytes = bytes("validated-map-template");
        byte[] realmSnapshotBytes = bytes("validated-realm-snapshot");
        ArtifactPin artifactPin = pin("artifact.map.certification", artifactBytes, "map-template-v1");
        ArtifactPin realmSnapshotPin = pin("artifact.realm.snapshot.certification", realmSnapshotBytes, "realm-state-v1");

        StoredObject artifact = adapter.put(artifactPin, artifactBytes);
        StoredObject realmSnapshot = adapter.put(realmSnapshotPin, realmSnapshotBytes);

        assertEquals(ArtifactBlobLayout.objectAddress(OBJECT_BUCKET, artifactPin), artifact.address());
        assertEquals(ArtifactBlobLayout.objectAddress(OBJECT_BUCKET, realmSnapshotPin), realmSnapshot.address());
        assertArrayEquals(artifactBytes, adapter.read(artifact.address()).orElseThrow());
        assertArrayEquals(realmSnapshotBytes, adapter.read(realmSnapshot.address()).orElseThrow());
        assertTrue(adapter.exists(artifact.address()));
        assertTrue(adapter.exists(realmSnapshot.address()));
    }

    private static void assertRedeliversBeforeOffsetCommit(String bootstrapServers) {
        try (KafkaConsumer<String, String> consumer = consumer(bootstrapServers, GROUP_ID)) {
            consumer.subscribe(List.of(COMMAND_TOPIC));
            var source = new KafkaAuthorityCommandSource<>(
                    consumer,
                    Duration.ofSeconds(10),
                    StoreAdapterCertificationMatrixTest::decodeCommand);
            var delivery = source.poll().orElseThrow();
            assertEquals(COMMAND_TOPIC, delivery.offset().source());
            assertEquals(0, delivery.offset().position());
            assertEquals(AGGREGATE_ID, delivery.command().envelope().aggregateId());
        }
    }

    private static AuthorityRuntimeWorker<CertState, CertCommand, CertReceipt> worker(
            KafkaConsumer<String, String> consumer,
            KafkaProducer<String, String> producer,
            CqlSession cqlSession,
            UnifiedJedis valkey,
            DataSource dataSource,
            ValkeyIdempotencyLedger<CertState, CertReceipt> idempotencyLedger) {
        return new AuthorityRuntimeWorker<>(
                new KafkaAuthorityCommandSource<>(consumer, Duration.ofSeconds(10), StoreAdapterCertificationMatrixTest::decodeCommand),
                new JdbcAuthorityRecordStore<>(
                        dataSource,
                        new JdbcAuthorityRecordStoreConfig("cert_authority_records"),
                        CertState.codec(),
                        () -> new AuthorityRecord<>(new Revision(0), FENCING_EPOCH, new CertState(0))),
                domainHandler(idempotencyLedger),
                new CassandraAuthorityProjectionWriter<>(
                        cqlSession,
                        (command, decision) -> SimpleStatement.newInstance(
                                "INSERT INTO fulcrum.cert_projection (aggregate_id, total, revision) VALUES (?, ?, ?)",
                                command.envelope().aggregateId().value(),
                                decision.state().total(),
                                decision.revision().value())),
                AuthorityEmissionSinks.composite(
                        new KafkaAuthorityEmissionSink(
                                producer,
                                new KafkaAuthorityEmissionTopics(EVENT_TOPIC, STATE_TOPIC, RESPONSE_TOPIC),
                                Duration.ofSeconds(10)),
                        new ValkeyAuthorityCacheSink(valkey)),
                new JdbcAuthorityDecisionRecorder<>(
                        dataSource,
                        new JdbcAuthorityDecisionRecorderConfig("cert_authority_decisions"),
                        decision -> "status=" + decision.status().name() + ";revision=" + decision.revision().value()),
                new KafkaAuthorityOffsetCommitter(consumer));
    }

    private static AuthorityDomainHandler<CertState, CertCommand, CertReceipt> domainHandler(
            ValkeyIdempotencyLedger<CertState, CertReceipt> idempotencyLedger) {
        AuthorityCommandProcessor<CertState, CertCommand, CertReceipt> processor = new AuthorityCommandProcessor<>(
                idempotencyLedger,
                reason -> new CertReceipt("REJECTED:" + reason.name(), -1),
                StoreAdapterCertificationMatrixTest::applyMutation);
        return processor::process;
    }

    private static AuthorityMutationResult<CertState, CertReceipt> applyMutation(
            AuthorityCommand<CertCommand> command,
            AuthorityRecord<CertState> current) {
        long nextRevision = current.revision().value() + 1;
        CertState state = new CertState(current.state().total() + command.envelope().payload().amount());
        CertReceipt receipt = new CertReceipt("ACCEPTED", state.total());
        return new AuthorityMutationResult<>(
                new Revision(nextRevision),
                state,
                receipt,
                List.of(
                        new AuthorityEmission(AuthorityEmissionKind.EVENT, command.envelope().aggregateId().value(), "accepted:" + state.total()),
                        new AuthorityEmission(AuthorityEmissionKind.STATE, command.envelope().aggregateId().value(), "total=" + state.total()),
                        new AuthorityEmission(AuthorityEmissionKind.RESPONSE, command.envelope().commandId().value(), "status=ACCEPTED;revision=" + nextRevision),
                        new AuthorityEmission(AuthorityEmissionKind.CACHE_WRITE, command.envelope().aggregateId().value(), "total=" + state.total())));
    }

    private static AuthorityCommand<CertCommand> decodeCommand(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record) {
        Map<String, String> fields = record.value().lines()
                .filter(line -> !line.isBlank())
                .map(line -> line.split("=", 2))
                .collect(Collectors.toMap(parts -> parts[0], parts -> parts[1]));
        CertCommand payload = new CertCommand(Integer.parseInt(fields.get("amount")));
        return new AuthorityCommand<>(
                new CommandEnvelope<>(
                        new CommandId(fields.get("commandId")),
                        new IdempotencyKey(fields.get("idempotencyKey")),
                        new PrincipalId(fields.get("principalId")),
                        new AggregateId(record.key()),
                        new ContractName("store-adapter-certification"),
                        new CommandName("apply-cert-total"),
                        trace(fields.get("commandId")),
                        Optional.empty(),
                        payload),
                new PrincipalId(fields.get("principalId")),
                Long.parseLong(fields.get("fencingEpoch")),
                Optional.of(new Revision(Long.parseLong(fields.get("expectedRevision")))),
                fields.get("payloadFingerprint"),
                NOW);
    }

    private static String commandPayload(
            String commandId,
            String idempotencyKey,
            int amount,
            long expectedRevision,
            String payloadFingerprint) {
        return """
                commandId=%s
                idempotencyKey=%s
                principalId=%s
                amount=%d
                fencingEpoch=%d
                expectedRevision=%d
                payloadFingerprint=%s
                """.formatted(commandId, idempotencyKey, PRINCIPAL.value(), amount, FENCING_EPOCH, expectedRevision, payloadFingerprint);
    }

    private static void createTopics(String bootstrapServers) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrapServers))) {
            admin.createTopics(List.of(
                    new NewTopic(COMMAND_TOPIC, 1, (short) 1),
                    new NewTopic(EVENT_TOPIC, 1, (short) 1),
                    new NewTopic(STATE_TOPIC, 1, (short) 1),
                    new NewTopic(RESPONSE_TOPIC, 1, (short) 1))).all().get(20, TimeUnit.SECONDS);
        }
    }

    private static void createPostgresSchema(FulcrumSubstrateStack stack) {
        stack.executePostgres("""
                CREATE TABLE cert_authority_records (
                    aggregate_id TEXT PRIMARY KEY,
                    revision BIGINT NOT NULL,
                    fencing_epoch BIGINT NOT NULL,
                    state_payload TEXT NOT NULL
                );
                CREATE TABLE cert_authority_decisions (
                    command_id TEXT PRIMARY KEY,
                    aggregate_id TEXT NOT NULL,
                    source_topic TEXT NOT NULL,
                    source_partition INTEGER NOT NULL,
                    source_offset BIGINT NOT NULL,
                    status TEXT NOT NULL,
                    rejection_reason TEXT NOT NULL,
                    revision BIGINT NOT NULL,
                    replayed BOOLEAN NOT NULL,
                    trace_id TEXT NOT NULL,
                    decision_payload TEXT NOT NULL
                );
                """);
    }

    private static void createCassandraSchema(FulcrumSubstrateStack stack) {
        stack.executeCassandra("""
                CREATE KEYSPACE IF NOT EXISTS fulcrum
                WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

                CREATE TABLE IF NOT EXISTS fulcrum.cert_projection (
                    aggregate_id text PRIMARY KEY,
                    total int,
                    revision bigint
                );
                """);
    }

    private static void sendCommand(String bootstrapServers, String payload) throws Exception {
        try (KafkaProducer<String, String> producer = producer(bootstrapServers)) {
            producer.send(new ProducerRecord<>(COMMAND_TOPIC, AGGREGATE_ID.value(), payload)).get(20, TimeUnit.SECONDS);
        }
    }

    private static String cassandraProjection(FulcrumSubstrateStack stack) {
        String output = stack.queryCassandra("""
                SELECT aggregate_id, total, revision
                FROM fulcrum.cert_projection
                WHERE aggregate_id = 'cert:aggregate:1';
                """);
        assertTrue(output.contains("cert:aggregate:1"));
        assertTrue(output.contains("3"));
        assertTrue(output.contains("1"));
        return "cert:aggregate:1|3|1";
    }

    private static List<String> drainTopic(String bootstrapServers, String topic, int expectedMinimum) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, topic + "-certification-drain-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()))) {
            consumer.subscribe(List.of(topic));
            List<String> values = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline && values.size() < expectedMinimum) {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> values.add(record.value()));
            }
            return values;
        }
    }

    private static PGSimpleDataSource dataSource(FulcrumSubstrateStack stack) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(stack.postgresJdbcUrl());
        dataSource.setUser(stack.postgresUsername());
        dataSource.setPassword(stack.postgresPassword());
        return dataSource;
    }

    private static CqlSession cassandraSession(FulcrumSubstrateStack stack) {
        return CqlSession.builder()
                .addContactPoint(new InetSocketAddress(stack.cassandraHost(), stack.cassandraPort()))
                .withLocalDatacenter("datacenter1")
                .build();
    }

    private static KafkaProducer<String, String> producer(String bootstrapServers) {
        return new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()));
    }

    private static KafkaConsumer<String, String> consumer(String bootstrapServers, String groupId) {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, groupId,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
    }

    private static ArtifactPin pin(String artifactId, byte[] bytes, String compatibility) {
        return new ArtifactPin(new ArtifactId(artifactId), sha256(bytes), compatibility);
    }

    private static TraceEnvelope trace(String spanId) {
        return new TraceEnvelope(
                "trace-store-adapter-certification",
                spanId,
                Optional.empty(),
                NOW,
                "store-adapter-certification",
                new InstanceId("instance-store-adapter-certification"));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }

    private record CertCommand(int amount) implements CommandPayload {
    }

    private record CertState(int total) {
        private static sh.harold.fulcrum.data.store.postgresql.JdbcAuthorityStateCodec<CertState> codec() {
            return new sh.harold.fulcrum.data.store.postgresql.JdbcAuthorityStateCodec<>() {
                @Override
                public String encode(CertState state) {
                    return Integer.toString(state.total());
                }

                @Override
                public CertState decode(String payload) {
                    return new CertState(Integer.parseInt(payload));
                }
            };
        }
    }

    private record CertReceipt(String status, long total) {
    }

    private static final class CertStoredDecisionCodec implements ValkeyStoredAuthorityDecisionCodec<CertState, CertReceipt> {
        @Override
        public String encode(StoredAuthorityDecision<CertState, CertReceipt> stored) {
            AuthorityDecision<CertState, CertReceipt> decision = stored.decision();
            return String.join("|",
                    stored.payloadFingerprint(),
                    decision.status().name(),
                    decision.rejectionReason().map(Enum::name).orElse(""),
                    Long.toString(decision.revision().value()),
                    Integer.toString(decision.state().total()),
                    decision.response().status(),
                    Long.toString(decision.response().total()),
                    decision.traceEnvelope().traceId(),
                    decision.traceEnvelope().spanId(),
                    decision.traceEnvelope().createdAt().toString(),
                    decision.traceEnvelope().originService(),
                    decision.traceEnvelope().originInstanceId().value());
        }

        @Override
        public StoredAuthorityDecision<CertState, CertReceipt> decode(String payload) {
            String[] fields = payload.split("\\|", -1);
            Revision revision = new Revision(Long.parseLong(fields[3]));
            CertState state = new CertState(Integer.parseInt(fields[4]));
            CertReceipt receipt = new CertReceipt(fields[5], Long.parseLong(fields[6]));
            TraceEnvelope trace = new TraceEnvelope(
                    fields[7],
                    fields[8],
                    Optional.empty(),
                    Instant.parse(fields[9]),
                    fields[10],
                    new InstanceId(fields[11]));
            AuthorityDecision<CertState, CertReceipt> decision;
            if (AuthorityDecisionStatus.REJECTED.name().equals(fields[1])) {
                decision = AuthorityDecision.rejected(
                        AuthorityRejectionReason.valueOf(fields[2]),
                        revision,
                        state,
                        receipt,
                        trace);
            } else {
                decision = AuthorityDecision.accepted(revision, state, receipt, List.of(), trace);
            }
            return new StoredAuthorityDecision<>(fields[0], decision);
        }
    }
}
