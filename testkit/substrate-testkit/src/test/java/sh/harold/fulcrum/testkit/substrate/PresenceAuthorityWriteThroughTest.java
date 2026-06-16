package sh.harold.fulcrum.testkit.substrate;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.PresenceId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.AuthorityEmission;
import sh.harold.fulcrum.data.authority.AuthorityEmissionKind;
import sh.harold.fulcrum.data.authority.InMemoryIdempotencyLedger;
import sh.harold.fulcrum.data.presence.ClaimPresence;
import sh.harold.fulcrum.data.presence.PresenceAuthority;
import sh.harold.fulcrum.data.presence.PresenceReceipt;
import sh.harold.fulcrum.data.presence.PresenceSnapshot;
import sh.harold.fulcrum.data.presence.PresenceState;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PresenceAuthorityWriteThroughTest {
    private static final String COMMAND_TOPIC = "cmd.presence";
    private static final String EVENT_TOPIC = "evt.presence";
    private static final String STATE_TOPIC = "state.presence";
    private static final String RESPONSE_TOPIC = "rsp.presence";
    private static final String AUTHORITY_GROUP = "presence-authority-write-through";
    private static final Instant REQUESTED_AT = Instant.parse("2026-06-16T09:00:00Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-06-16T09:00:01Z");

    @Test
    void claimPresenceWritesCassandraHotProjectionAndValkeyCacheBeforeOffsetCommit() throws Exception {
        try (FulcrumSubstrateStack stack = FulcrumSubstrateStack.create().start()) {
            createTopics(stack.kafkaBootstrapServers());
            createSchema(stack);

            PresenceCommandRecord command = PresenceCommandRecord.fixture();
            sendCommand(stack.kafkaBootstrapServers(), command);

            PresenceAuthorityWorker worker = new PresenceAuthorityWorker(stack, AUTHORITY_GROUP);
            AuthorityDecision<PresenceState, PresenceReceipt> decision = worker.handleNext();

            assertEquals(AuthorityDecisionStatus.ACCEPTED, decision.status());
            assertEquals(new Revision(1), decision.revision());

            String cassandraProjection = stack.queryCassandra("""
                    SELECT presence_id, owner_instance_id, session_id, route_id, revision
                    FROM fulcrum.presence_hot
                    WHERE subject_id = '%s';
                    """.formatted(cql(command.subjectId())));
            assertTrue(cassandraProjection.contains(command.presenceId()));
            assertTrue(cassandraProjection.contains(command.ownerInstanceId()));
            assertTrue(cassandraProjection.contains(command.sessionId()));
            assertTrue(cassandraProjection.contains(command.routeId()));
            assertTrue(cassandraProjection.contains("1"));

            String cachePayload = decision.emissions().stream()
                    .filter(emission -> emission.kind() == AuthorityEmissionKind.CACHE_WRITE)
                    .map(AuthorityEmission::payload)
                    .findFirst()
                    .orElseThrow();
            assertEquals(cachePayload, stack.getValkey(PresenceAuthority.cacheKey(new SubjectId(UUID.fromString(command.subjectId())))));

            List<String> events = drainTopic(stack.kafkaBootstrapServers(), EVENT_TOPIC, 1);
            List<String> states = drainTopic(stack.kafkaBootstrapServers(), STATE_TOPIC, 1);
            List<String> responses = drainTopic(stack.kafkaBootstrapServers(), RESPONSE_TOPIC, 1);

            assertEquals(1, events.size());
            assertEquals(1, states.size());
            assertEquals(1, responses.size());
            assertTrue(events.getFirst().contains("presenceId=" + command.presenceId()));
            assertEquals(cachePayload, states.getFirst());
            assertTrue(responses.getFirst().contains("status=ACCEPTED"));
            assertTrue(responses.getFirst().contains("subjectId=" + command.subjectId()));
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

    private static void createSchema(FulcrumSubstrateStack stack) {
        stack.executeCassandra("""
                CREATE KEYSPACE IF NOT EXISTS fulcrum
                WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

                CREATE TABLE IF NOT EXISTS fulcrum.presence_hot (
                    subject_id text PRIMARY KEY,
                    presence_id text,
                    owner_instance_id text,
                    session_id text,
                    route_id text,
                    observed_at text,
                    expires_at text,
                    revision bigint
                );
                """);
    }

    private static void sendCommand(String bootstrapServers, PresenceCommandRecord command) throws Exception {
        try (KafkaProducer<String, String> producer = producer(bootstrapServers)) {
            producer.send(new ProducerRecord<>(COMMAND_TOPIC, command.aggregateId(), command.encode())).get(20, TimeUnit.SECONDS);
        }
    }

    private static List<String> drainTopic(String bootstrapServers, String topic, int expectedMinimum) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, topic + "-drain-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()))) {
            consumer.subscribe(List.of(topic));
            List<String> values = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline && values.size() < expectedMinimum) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
                for (ConsumerRecord<String, String> record : records) {
                    values.add(record.value());
                }
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

    private static TraceEnvelope traceEnvelope(String traceId) {
        return new TraceEnvelope(
                traceId,
                "span-presence-authority",
                Optional.empty(),
                REQUESTED_AT,
                "presence-authority-test",
                new InstanceId("instance-presence-authority-test"));
    }

    private static String cql(String value) {
        return value.replace("'", "''");
    }

    private static final class PresenceAuthorityWorker {
        private final FulcrumSubstrateStack stack;
        private final String groupId;

        private PresenceAuthorityWorker(FulcrumSubstrateStack stack, String groupId) {
            this.stack = stack;
            this.groupId = groupId;
        }

        private AuthorityDecision<PresenceState, PresenceReceipt> handleNext() throws Exception {
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, stack.kafkaBootstrapServers(),
                    ConsumerConfig.GROUP_ID_CONFIG, groupId,
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
                 KafkaProducer<String, String> producer = producer(stack.kafkaBootstrapServers())) {
                consumer.subscribe(List.of(COMMAND_TOPIC));
                ConsumerRecord<String, String> record = pollOne(consumer);
                PresenceCommandRecord commandRecord = PresenceCommandRecord.decode(record.value());
                PresenceAuthority authority = new PresenceAuthority(new InMemoryIdempotencyLedger<>());
                AuthorityDecision<PresenceState, PresenceReceipt> decision = authority.handle(
                        commandRecord.toAuthorityCommand(),
                        PresenceAuthority.emptyRecord(commandRecord.fencingEpoch()));

                writeCassandraProjection(decision);
                publishEmissions(producer, decision.emissions());
                storeCacheWrites(decision.emissions());
                consumer.commitSync();
                return decision;
            }
        }

        private static ConsumerRecord<String, String> pollOne(KafkaConsumer<String, String> consumer) {
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
                if (!records.isEmpty()) {
                    return records.iterator().next();
                }
            }
            throw new IllegalStateException("No presence command received");
        }

        private void writeCassandraProjection(AuthorityDecision<PresenceState, PresenceReceipt> decision) {
            PresenceSnapshot snapshot = decision.state().current().orElseThrow();
            stack.executeCassandra("""
                    INSERT INTO fulcrum.presence_hot (
                        subject_id,
                        presence_id,
                        owner_instance_id,
                        session_id,
                        route_id,
                        observed_at,
                        expires_at,
                        revision
                    ) VALUES (
                        '%s',
                        '%s',
                        '%s',
                        '%s',
                        '%s',
                        '%s',
                        '%s',
                        %d
                    );
                    """.formatted(
                    cql(snapshot.subjectId().value().toString()),
                    cql(snapshot.presenceId().value()),
                    cql(snapshot.ownerInstanceId().value()),
                    cql(snapshot.sessionId().map(SessionId::value).orElse("")),
                    cql(snapshot.routeId().map(RouteId::value).orElse("")),
                    cql(snapshot.observedAt().toString()),
                    cql(snapshot.expiresAt().toString()),
                    decision.revision().value()));
        }

        private void publishEmissions(
                KafkaProducer<String, String> producer,
                List<AuthorityEmission> emissions) throws Exception {
            for (AuthorityEmission emission : emissions) {
                switch (emission.kind()) {
                    case EVENT -> producer.send(new ProducerRecord<>(EVENT_TOPIC, emission.key(), emission.payload())).get(20, TimeUnit.SECONDS);
                    case STATE -> producer.send(new ProducerRecord<>(STATE_TOPIC, emission.key(), emission.payload())).get(20, TimeUnit.SECONDS);
                    case RESPONSE -> producer.send(new ProducerRecord<>(RESPONSE_TOPIC, emission.key(), emission.payload())).get(20, TimeUnit.SECONDS);
                    case CACHE_WRITE -> {
                    }
                }
            }
        }

        private void storeCacheWrites(List<AuthorityEmission> emissions) {
            emissions.stream()
                    .filter(emission -> emission.kind() == AuthorityEmissionKind.CACHE_WRITE)
                    .forEach(emission -> stack.setValkey(emission.key(), emission.payload()));
        }
    }

    private record PresenceCommandRecord(
            String commandId,
            String idempotencyKey,
            String declaredPrincipalId,
            String authenticatedPrincipalId,
            String subjectId,
            String presenceId,
            String ownerInstanceId,
            String sessionId,
            String routeId,
            String observedAt,
            String expiresAt,
            long fencingEpoch,
            long expectedRevision,
            String payloadFingerprint) {
        private static PresenceCommandRecord fixture() {
            return new PresenceCommandRecord(
                    "command-presence-claim-1",
                    "idempotency-presence-claim-1",
                    "principal-velocity-edge",
                    "principal-velocity-edge",
                    "11111111-1111-1111-1111-111111111111",
                    "presence-edge-1",
                    "instance-velocity-1",
                    "session-lobby-1",
                    "route-lobby-1",
                    RECEIVED_AT.toString(),
                    "2026-06-16T09:01:00Z",
                    12,
                    0,
                    "claim-presence:11111111-1111-1111-1111-111111111111");
        }

        private String aggregateId() {
            return PresenceAuthority.aggregateId(new SubjectId(UUID.fromString(subjectId))).value();
        }

        private String encode() {
            return "commandId=" + commandId
                    + "\nidempotencyKey=" + idempotencyKey
                    + "\ndeclaredPrincipalId=" + declaredPrincipalId
                    + "\nauthenticatedPrincipalId=" + authenticatedPrincipalId
                    + "\nsubjectId=" + subjectId
                    + "\npresenceId=" + presenceId
                    + "\nownerInstanceId=" + ownerInstanceId
                    + "\nsessionId=" + sessionId
                    + "\nrouteId=" + routeId
                    + "\nobservedAt=" + observedAt
                    + "\nexpiresAt=" + expiresAt
                    + "\nfencingEpoch=" + fencingEpoch
                    + "\nexpectedRevision=" + expectedRevision
                    + "\npayloadFingerprint=" + payloadFingerprint;
        }

        private static PresenceCommandRecord decode(String value) {
            Map<String, String> fields = value.lines()
                    .map(line -> line.split("=", 2))
                    .collect(java.util.stream.Collectors.toMap(parts -> parts[0], parts -> parts[1]));
            return new PresenceCommandRecord(
                    fields.get("commandId"),
                    fields.get("idempotencyKey"),
                    fields.get("declaredPrincipalId"),
                    fields.get("authenticatedPrincipalId"),
                    fields.get("subjectId"),
                    fields.get("presenceId"),
                    fields.get("ownerInstanceId"),
                    fields.get("sessionId"),
                    fields.get("routeId"),
                    fields.get("observedAt"),
                    fields.get("expiresAt"),
                    Long.parseLong(fields.get("fencingEpoch")),
                    Long.parseLong(fields.get("expectedRevision")),
                    fields.get("payloadFingerprint"));
        }

        private AuthorityCommand<ClaimPresence> toAuthorityCommand() {
            ClaimPresence payload = new ClaimPresence(
                    new PresenceId(presenceId),
                    new SubjectId(UUID.fromString(subjectId)),
                    new InstanceId(ownerInstanceId),
                    Optional.of(new SessionId(sessionId)),
                    Optional.of(new RouteId(routeId)),
                    Instant.parse(observedAt),
                    Instant.parse(expiresAt));
            CommandEnvelope<ClaimPresence> envelope = new CommandEnvelope<>(
                    new CommandId(commandId),
                    new IdempotencyKey(idempotencyKey),
                    new PrincipalId(declaredPrincipalId),
                    new AggregateId(aggregateId()),
                    new ContractName("presence"),
                    new CommandName("claim-presence"),
                    traceEnvelope("trace-presence-command"),
                    Optional.empty(),
                    payload);
            return new AuthorityCommand<>(
                    envelope,
                    new PrincipalId(authenticatedPrincipalId),
                    fencingEpoch,
                    Optional.of(new Revision(expectedRevision)),
                    payloadFingerprint,
                    RECEIVED_AT);
        }
    }
}
