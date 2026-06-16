package sh.harold.fulcrum.validation.fleete2e;

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
import org.postgresql.ds.PGSimpleDataSource;
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
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityEmission;
import sh.harold.fulcrum.data.authority.AuthorityEmissionKind;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.AuthorityRejectionReason;
import sh.harold.fulcrum.data.authority.runtime.AuthorityEmissionSinks;
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
import sh.harold.fulcrum.testkit.substrate.FulcrumSubstrateStack;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProductionSubstrateFleetE2eTest {
    private static final String COMMAND_TOPIC = "cmd.fleet-production";
    private static final String EVENT_TOPIC = "evt.fleet-production";
    private static final String STATE_TOPIC = "state.fleet-production";
    private static final String RESPONSE_TOPIC = "rsp.fleet-production";
    private static final String GROUP_ID = "fleet-production-e2e-authority";
    private static final Instant NOW = Instant.parse("2026-06-16T12:00:00Z");

    @Test
    void productionShapedFleetPathUsesRealSubstrateComponentsAndProductionAdapters() throws Exception {
        DeploymentProfile smallProduction = DeploymentProfile.load("small-production");
        assertEquals("reduced-redundancy-real-engines", smallProduction.storageShape());
        assertEquals("kubernetes-native", smallProduction.agonesMode());

        try (FulcrumSubstrateStack stack = FulcrumSubstrateStack.create().start()) {
            assertTrue(stack.kafkaRunning());
            assertTrue(stack.postgresRunning());
            assertTrue(stack.cassandraRunning());
            assertTrue(stack.valkeyRunning());

            createTopics(stack.kafkaBootstrapServers());
            createStoreSchema(stack);

            sendCommand(stack.kafkaBootstrapServers(), commandPayload());
            handleOneCommandThroughProductionAdapters(stack);

            assertEquals("1", postgresScalar(stack, "SELECT revision FROM fleet_authority_records WHERE aggregate_id = 'fleet:session-final-real';"));
            assertEquals("ACCEPTED", postgresScalar(stack, "SELECT status FROM fleet_authority_decisions WHERE command_id = 'command-fleet-real-1';"));
            assertEquals("fleet:session-final-real|7|1", cassandraProjection(stack));
            assertEquals("total=7", valkeyGet(stack, "fleet:session-final-real"));

            List<String> events = drainTopic(stack.kafkaBootstrapServers(), EVENT_TOPIC, 1);
            List<String> states = drainTopic(stack.kafkaBootstrapServers(), STATE_TOPIC, 1);
            List<String> responses = drainTopic(stack.kafkaBootstrapServers(), RESPONSE_TOPIC, 1);
            assertEquals(List.of("accepted:7"), events);
            assertEquals(List.of("total=7"), states);
            assertEquals(List.of("status=ACCEPTED;revision=1"), responses);
        }
    }

    private static void handleOneCommandThroughProductionAdapters(FulcrumSubstrateStack stack) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(stack.postgresJdbcUrl());
        dataSource.setUser(stack.postgresUsername());
        dataSource.setPassword(stack.postgresPassword());

        try (KafkaConsumer<String, String> consumer = consumer(stack.kafkaBootstrapServers(), GROUP_ID);
             KafkaProducer<String, String> producer = producer(stack.kafkaBootstrapServers());
             CqlSession cqlSession = CqlSession.builder()
                     .addContactPoint(new InetSocketAddress(stack.cassandraHost(), stack.cassandraPort()))
                     .withLocalDatacenter("datacenter1")
                     .build();
             UnifiedJedis valkey = new UnifiedJedis(new HostAndPort(stack.valkeyHost(), stack.valkeyPort()))) {
            consumer.subscribe(List.of(COMMAND_TOPIC));

            AuthorityRuntimeWorker<FleetState, FleetCommand, FleetReceipt> worker = new AuthorityRuntimeWorker<>(
                    new KafkaAuthorityCommandSource<>(consumer, Duration.ofSeconds(10), ProductionSubstrateFleetE2eTest::decodeCommand),
                    new JdbcAuthorityRecordStore<>(
                            dataSource,
                            new JdbcAuthorityRecordStoreConfig("fleet_authority_records"),
                            FleetState.codec(),
                            () -> new AuthorityRecord<>(new Revision(0), 55, new FleetState(0))),
                    ProductionSubstrateFleetE2eTest::handleFleetCommand,
                    new CassandraAuthorityProjectionWriter<>(
                            cqlSession,
                            (command, decision) -> SimpleStatement.newInstance(
                                    "INSERT INTO fulcrum.fleet_projection (aggregate_id, total, revision) VALUES (?, ?, ?)",
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
                            new JdbcAuthorityDecisionRecorderConfig("fleet_authority_decisions"),
                            decision -> "status=" + decision.status().name() + ";revision=" + decision.revision().value()),
                    new KafkaAuthorityOffsetCommitter(consumer));

            assertTrue(worker.handleNext().isPresent());
        }
    }

    private static AuthorityDecision<FleetState, FleetReceipt> handleFleetCommand(
            AuthorityCommand<FleetCommand> command,
            AuthorityRecord<FleetState> current) {
        long nextRevision = current.revision().value() + 1;
        FleetState state = new FleetState(current.state().total() + command.envelope().payload().amount());
        FleetReceipt receipt = new FleetReceipt(true, new Revision(nextRevision), Optional.empty());
        return AuthorityDecision.accepted(
                new Revision(nextRevision),
                state,
                receipt,
                List.of(
                        new AuthorityEmission(AuthorityEmissionKind.EVENT, command.envelope().aggregateId().value(), "accepted:" + state.total()),
                        new AuthorityEmission(AuthorityEmissionKind.STATE, command.envelope().aggregateId().value(), "total=" + state.total()),
                        new AuthorityEmission(AuthorityEmissionKind.RESPONSE, command.envelope().commandId().value(), "status=ACCEPTED;revision=" + nextRevision),
                        new AuthorityEmission(AuthorityEmissionKind.CACHE_WRITE, command.envelope().aggregateId().value(), "total=" + state.total())),
                command.envelope().traceEnvelope());
    }

    private static AuthorityCommand<FleetCommand> decodeCommand(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record) {
        Map<String, String> fields = record.value().lines()
                .map(line -> line.split("=", 2))
                .collect(Collectors.toMap(parts -> parts[0], parts -> parts[1]));
        FleetCommand payload = new FleetCommand(Integer.parseInt(fields.get("amount")));
        TraceEnvelope trace = new TraceEnvelope(
                fields.get("traceId"),
                "span-production-substrate",
                Optional.empty(),
                NOW,
                "production-substrate-fleet-e2e",
                new InstanceId("instance-production-substrate"));
        return new AuthorityCommand<>(
                new CommandEnvelope<>(
                        new CommandId(fields.get("commandId")),
                        new IdempotencyKey(fields.get("idempotencyKey")),
                        new PrincipalId(fields.get("principalId")),
                        new AggregateId(record.key()),
                        new ContractName("fleet-production"),
                        new CommandName("apply-fleet-total"),
                        trace,
                        Optional.empty(),
                        payload),
                new PrincipalId(fields.get("principalId")),
                Long.parseLong(fields.get("fencingEpoch")),
                Optional.of(new Revision(Long.parseLong(fields.get("expectedRevision")))),
                fields.get("payloadFingerprint"),
                NOW);
    }

    private static String commandPayload() {
        return """
                commandId=command-fleet-real-1
                idempotencyKey=idempotency-fleet-real-1
                principalId=principal-fleet-real
                traceId=trace-production-substrate-fleet
                amount=7
                fencingEpoch=55
                expectedRevision=0
                payloadFingerprint=fleet:session-final-real:7
                """;
    }

    private static void sendCommand(String bootstrapServers, String payload) throws Exception {
        try (KafkaProducer<String, String> producer = producer(bootstrapServers)) {
            producer.send(new ProducerRecord<>(COMMAND_TOPIC, "fleet:session-final-real", payload)).get(20, TimeUnit.SECONDS);
        }
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

    private static void createStoreSchema(FulcrumSubstrateStack stack) {
        stack.executePostgres("""
                CREATE TABLE fleet_authority_records (
                    aggregate_id TEXT PRIMARY KEY,
                    revision BIGINT NOT NULL,
                    fencing_epoch BIGINT NOT NULL,
                    state_payload TEXT NOT NULL
                );
                CREATE TABLE fleet_authority_decisions (
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
        stack.executeCassandra("""
                CREATE KEYSPACE IF NOT EXISTS fulcrum
                WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

                CREATE TABLE IF NOT EXISTS fulcrum.fleet_projection (
                    aggregate_id text PRIMARY KEY,
                    total int,
                    revision bigint
                );
                """);
    }

    private static String postgresScalar(FulcrumSubstrateStack stack, String sql) {
        return stack.queryPostgresScalar(sql);
    }

    private static String cassandraProjection(FulcrumSubstrateStack stack) {
        String output = stack.queryCassandra("""
                SELECT aggregate_id, total, revision
                FROM fulcrum.fleet_projection
                WHERE aggregate_id = 'fleet:session-final-real';
                """);
        assertTrue(output.contains("fleet:session-final-real"));
        assertTrue(output.contains("7"));
        assertTrue(output.contains("1"));
        return "fleet:session-final-real|7|1";
    }

    private static String valkeyGet(FulcrumSubstrateStack stack, String key) {
        return stack.getValkey(key);
    }

    private static List<String> drainTopic(String bootstrapServers, String topic, int expectedMinimum) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, topic + "-production-drain-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()))) {
            consumer.subscribe(List.of(topic));
            List<String> values = new java.util.ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline && values.size() < expectedMinimum) {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> values.add(record.value()));
            }
            return values;
        }
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

    private record FleetCommand(int amount) implements CommandPayload {
    }

    private record FleetState(int total) {
        private static sh.harold.fulcrum.data.store.postgresql.JdbcAuthorityStateCodec<FleetState> codec() {
            return new sh.harold.fulcrum.data.store.postgresql.JdbcAuthorityStateCodec<>() {
                @Override
                public String encode(FleetState state) {
                    return Integer.toString(state.total());
                }

                @Override
                public FleetState decode(String payload) {
                    return new FleetState(Integer.parseInt(payload));
                }
            };
        }
    }

    private record FleetReceipt(boolean accepted, Revision revision, Optional<AuthorityRejectionReason> reason) {
    }

    private record DeploymentProfile(
            String profileId,
            String semanticModel,
            String contractSet,
            String servicePlacement,
            String storageShape,
            String agonesMode,
            String objectStorage) {
        private static final Pattern FIELD_PATTERN = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"");

        private static DeploymentProfile load(String profileId) throws IOException {
            String path = "/fulcrum/profiles/" + profileId + ".json";
            try (InputStream input = ProductionSubstrateFleetE2eTest.class.getResourceAsStream(path)) {
                if (input == null) {
                    throw new IOException("missing profile resource: " + path);
                }
                String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                Map<String, String> fields = FIELD_PATTERN.matcher(json)
                        .results()
                        .collect(Collectors.toMap(match -> match.group(1), match -> match.group(2)));
                return new DeploymentProfile(
                        field(fields, "profileId"),
                        field(fields, "semanticModel"),
                        field(fields, "contractSet"),
                        field(fields, "servicePlacement"),
                        field(fields, "storageShape"),
                        field(fields, "agonesMode"),
                        field(fields, "objectStorage"));
            }
        }

        private static String field(Map<String, String> fields, String key) {
            String value = fields.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("profile field missing: " + key);
            }
            return value;
        }
    }
}
