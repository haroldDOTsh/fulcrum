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
import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityCommandProcessor;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.AuthorityEmission;
import sh.harold.fulcrum.data.authority.AuthorityEmissionKind;
import sh.harold.fulcrum.data.authority.AuthorityMutationResult;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.AuthorityRejectionReason;
import sh.harold.fulcrum.data.authority.IdempotencyLedger;
import sh.harold.fulcrum.data.authority.StoredAuthorityDecision;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuthorityFencingRebalanceTest {
    private static final String COMMAND_TOPIC = "cmd.authority-fencing-proof";
    private static final String EVENT_TOPIC = "evt.authority-fencing-proof";
    private static final String STATE_TOPIC = "state.authority-fencing-proof";
    private static final String RESPONSE_TOPIC = "rsp.authority-fencing-proof";
    private static final String AUTHORITY_GROUP = "authority-fencing-rebalance";
    private static final Instant REQUESTED_AT = Instant.parse("2026-06-16T17:00:00Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-06-16T17:00:01Z");

    @Test
    void pausedAuthorityOwnerIsFencedAfterKafkaRedeliveryToNewOwner() throws Exception {
        try (FulcrumSubstrateStack stack = FulcrumSubstrateStack.create().start()) {
            createTopics(stack.kafkaBootstrapServers());
            createSchema(stack);

            FencingCommandRecord command = FencingCommandRecord.fixture();
            sendCommand(stack.kafkaBootstrapServers(), command);

            setOwnerEpoch(stack, 1);
            FencingAuthorityWorker pausedOwner = new FencingAuthorityWorker(stack, AUTHORITY_GROUP, "paused-owner", 1);
            ConsumerRecord<String, String> bufferedRecord = pausedOwner.readWithoutCommit();

            setOwnerEpoch(stack, 2);
            FencingAuthorityWorker currentOwner = new FencingAuthorityWorker(stack, AUTHORITY_GROUP, "current-owner", 2);
            AuthorityDecision<FencingState, String> accepted = currentOwner.handleNextAndCommit();

            AuthorityDecision<FencingState, String> stale = pausedOwner.resumeBuffered(bufferedRecord);

            assertEquals(AuthorityDecisionStatus.ACCEPTED, accepted.status());
            assertEquals(AuthorityDecisionStatus.REJECTED, stale.status());
            assertEquals(Optional.of(AuthorityRejectionReason.STALE_FENCING_EPOCH), stale.rejectionReason());
            assertFalse(stale.replayed(), "stale owner must not replay a stored result across an ownership change");
            assertEquals("1", stack.queryPostgresScalar("SELECT count(*) FROM fencing_projection;"));
            assertEquals("1", stack.queryPostgresScalar("SELECT revision FROM fencing_projection WHERE aggregate_id = 'aggregate-fencing-1';"));
            assertEquals("2", stack.queryPostgresScalar("SELECT fencing_epoch FROM fencing_projection WHERE aggregate_id = 'aggregate-fencing-1';"));
            assertEquals("1", stack.queryPostgresScalar("SELECT count(*) FROM fencing_idempotency;"));
            assertEquals("""
                    current-owner:ACCEPTED:2:2:false,\
                    paused-owner:REJECTED:1:2:false\
                    """, stack.queryPostgresScalar("""
                    SELECT string_agg(
                        owner_label || ':' ||
                        status || ':' ||
                        command_fencing_epoch || ':' ||
                        observed_fencing_epoch || ':' ||
                        replayed,
                        ',' ORDER BY owner_label)
                    FROM fencing_attempt;
                    """));

            List<String> events = drainTopic(stack.kafkaBootstrapServers(), EVENT_TOPIC, 1);
            List<String> states = drainTopic(stack.kafkaBootstrapServers(), STATE_TOPIC, 1);
            List<String> responses = drainTopic(stack.kafkaBootstrapServers(), RESPONSE_TOPIC, 1);

            assertEquals(1, events.size(), "stale owner must not emit an accepted event");
            assertEquals(1, states.size(), "stale owner must not emit compacted state");
            assertEquals(1, responses.size(), "only the current owner returns the accepted sync response");
            assertTrue(events.getFirst().contains("value=ready"));
            assertEquals(states.getFirst(), stack.getValkey("authority-fencing:aggregate-fencing-1"));
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
        stack.executePostgres("""
                CREATE TABLE fencing_owner_epoch (
                    owner_key TEXT PRIMARY KEY,
                    fencing_epoch BIGINT NOT NULL
                );
                CREATE TABLE fencing_projection (
                    aggregate_id TEXT PRIMARY KEY,
                    revision BIGINT NOT NULL,
                    state_value TEXT NOT NULL,
                    fencing_epoch BIGINT NOT NULL
                );
                CREATE TABLE fencing_idempotency (
                    idempotency_key TEXT PRIMARY KEY,
                    payload_fingerprint TEXT NOT NULL,
                    status TEXT NOT NULL,
                    rejection_reason TEXT NOT NULL,
                    revision BIGINT NOT NULL,
                    state_value TEXT NOT NULL,
                    response_value TEXT NOT NULL
                );
                CREATE TABLE fencing_attempt (
                    attempt_id TEXT PRIMARY KEY,
                    owner_label TEXT NOT NULL,
                    status TEXT NOT NULL,
                    rejection_reason TEXT NOT NULL,
                    command_fencing_epoch BIGINT NOT NULL,
                    observed_fencing_epoch BIGINT NOT NULL,
                    replayed TEXT NOT NULL
                );
                """);
    }

    private static void setOwnerEpoch(FulcrumSubstrateStack stack, long fencingEpoch) {
        stack.executePostgres("""
                INSERT INTO fencing_owner_epoch (owner_key, fencing_epoch)
                VALUES ('partition-0', %d)
                ON CONFLICT (owner_key) DO UPDATE SET fencing_epoch = EXCLUDED.fencing_epoch;
                """.formatted(fencingEpoch));
    }

    private static long ownerEpoch(FulcrumSubstrateStack stack) {
        return Long.parseLong(stack.queryPostgresScalar("""
                SELECT fencing_epoch
                FROM fencing_owner_epoch
                WHERE owner_key = 'partition-0';
                """));
    }

    private static void sendCommand(String bootstrapServers, FencingCommandRecord command) throws Exception {
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

    private static ConsumerRecord<String, String> pollOne(KafkaConsumer<String, String> consumer) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
            if (!records.isEmpty()) {
                return records.iterator().next();
            }
        }
        throw new IllegalStateException("No fencing command received");
    }

    private static String sql(String value) {
        return value.replace("'", "''");
    }

    private static TraceEnvelope traceEnvelope(String traceId) {
        return new TraceEnvelope(
                traceId,
                "span-authority-fencing",
                Optional.empty(),
                REQUESTED_AT,
                "authority-fencing-test",
                new InstanceId("instance-authority-fencing-test"));
    }

    private final class FencingAuthorityWorker implements AutoCloseable {
        private final FulcrumSubstrateStack stack;
        private final String groupId;
        private final String ownerLabel;
        private final long processingEpoch;
        private final KafkaConsumer<String, String> consumer;

        private FencingAuthorityWorker(
                FulcrumSubstrateStack stack,
                String groupId,
                String ownerLabel,
                long processingEpoch) {
            this.stack = stack;
            this.groupId = groupId;
            this.ownerLabel = ownerLabel;
            this.processingEpoch = processingEpoch;
            this.consumer = new KafkaConsumer<>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, stack.kafkaBootstrapServers(),
                    ConsumerConfig.GROUP_ID_CONFIG, groupId,
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
            this.consumer.subscribe(List.of(COMMAND_TOPIC));
        }

        private ConsumerRecord<String, String> readWithoutCommit() {
            ConsumerRecord<String, String> record = pollOne(consumer);
            close();
            return record;
        }

        private AuthorityDecision<FencingState, String> handleNextAndCommit() throws Exception {
            try (KafkaProducer<String, String> kafkaProducer = producer(stack.kafkaBootstrapServers())) {
                ConsumerRecord<String, String> record = pollOne(consumer);
                AuthorityDecision<FencingState, String> decision = handle(record);
                if (decision.status() == AuthorityDecisionStatus.ACCEPTED && !decision.replayed()) {
                    writeProjection(decision, processingEpoch);
                    publishEmissions(kafkaProducer, decision.emissions());
                    storeCacheWrites(decision.emissions());
                }
                recordAttempt(decision);
                consumer.commitSync();
                return decision;
            } finally {
                close();
            }
        }

        private AuthorityDecision<FencingState, String> resumeBuffered(ConsumerRecord<String, String> record) {
            AuthorityDecision<FencingState, String> decision = handle(record);
            recordAttempt(decision);
            return decision;
        }

        private AuthorityDecision<FencingState, String> handle(ConsumerRecord<String, String> record) {
            FencingCommandRecord commandRecord = FencingCommandRecord.decode(record.value());
            AuthorityCommandProcessor<FencingState, SetFencingValue, String> processor = new AuthorityCommandProcessor<>(
                    new PostgresFencingLedger(stack),
                    reason -> "rejected:" + reason.name(),
                    (command, current) -> {
                        FencingState state = new FencingState(command.envelope().payload().value());
                        Revision revision = new Revision(current.revision().value() + 1);
                        return new AuthorityMutationResult<>(
                                revision,
                                state,
                                "accepted:" + state.value() + ":" + revision.value(),
                                List.of(
                                        new AuthorityEmission(AuthorityEmissionKind.EVENT, command.envelope().aggregateId().value(), "value=" + state.value()),
                                        new AuthorityEmission(AuthorityEmissionKind.STATE, command.envelope().aggregateId().value(), state.wireValue(revision)),
                                        new AuthorityEmission(AuthorityEmissionKind.RESPONSE, command.envelope().commandId().value(), "accepted:" + state.value()),
                                        new AuthorityEmission(AuthorityEmissionKind.CACHE_WRITE, "authority-fencing:" + command.envelope().aggregateId().value(), state.wireValue(revision))));
                    });
            return processor.process(commandRecord.toAuthorityCommand(processingEpoch), currentRecord(commandRecord.aggregateId()));
        }

        private AuthorityRecord<FencingState> currentRecord(String aggregateId) {
            long currentOwnerEpoch = ownerEpoch(stack);
            String row = stack.queryPostgresScalar("""
                    SELECT revision || '|' || state_value
                    FROM fencing_projection
                    WHERE aggregate_id = '%s';
                    """.formatted(sql(aggregateId)));
            if (row.isBlank()) {
                return new AuthorityRecord<>(new Revision(0), currentOwnerEpoch, new FencingState("empty"));
            }
            String[] fields = row.split("\\|", -1);
            return new AuthorityRecord<>(
                    new Revision(Long.parseLong(fields[0])),
                    currentOwnerEpoch,
                    new FencingState(fields[1]));
        }

        private void writeProjection(AuthorityDecision<FencingState, String> decision, long fencingEpoch) {
            stack.executePostgres("""
                    INSERT INTO fencing_projection (aggregate_id, revision, state_value, fencing_epoch)
                    VALUES ('aggregate-fencing-1', %d, '%s', %d)
                    ON CONFLICT (aggregate_id) DO UPDATE
                    SET revision = EXCLUDED.revision,
                        state_value = EXCLUDED.state_value,
                        fencing_epoch = EXCLUDED.fencing_epoch;
                    """.formatted(
                    decision.revision().value(),
                    sql(decision.state().value()),
                    fencingEpoch));
        }

        private void publishEmissions(
                KafkaProducer<String, String> kafkaProducer,
                List<AuthorityEmission> emissions) throws Exception {
            for (AuthorityEmission emission : emissions) {
                switch (emission.kind()) {
                    case EVENT -> kafkaProducer.send(new ProducerRecord<>(EVENT_TOPIC, emission.key(), emission.payload())).get(20, TimeUnit.SECONDS);
                    case STATE -> kafkaProducer.send(new ProducerRecord<>(STATE_TOPIC, emission.key(), emission.payload())).get(20, TimeUnit.SECONDS);
                    case RESPONSE -> kafkaProducer.send(new ProducerRecord<>(RESPONSE_TOPIC, emission.key(), emission.payload())).get(20, TimeUnit.SECONDS);
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

        private void recordAttempt(AuthorityDecision<FencingState, String> decision) {
            stack.executePostgres("""
                    INSERT INTO fencing_attempt (
                        attempt_id,
                        owner_label,
                        status,
                        rejection_reason,
                        command_fencing_epoch,
                        observed_fencing_epoch,
                        replayed
                    ) VALUES (
                        '%s',
                        '%s',
                        '%s',
                        '%s',
                        %d,
                        %d,
                        '%s'
                    );
                    """.formatted(
                    sql(ownerLabel + "-" + processingEpoch),
                    sql(ownerLabel),
                    sql(decision.status().name()),
                    sql(decision.rejectionReason().map(Enum::name).orElse("")),
                    processingEpoch,
                    ownerEpoch(stack),
                    Boolean.toString(decision.replayed())));
        }

        @Override
        public void close() {
            consumer.close();
        }
    }

    private static final class PostgresFencingLedger implements IdempotencyLedger<FencingState, String> {
        private final FulcrumSubstrateStack stack;

        private PostgresFencingLedger(FulcrumSubstrateStack stack) {
            this.stack = stack;
        }

        @Override
        public Optional<StoredAuthorityDecision<FencingState, String>> find(IdempotencyKey idempotencyKey) {
            String row = stack.queryPostgresScalar("""
                    SELECT payload_fingerprint || '|' ||
                        status || '|' ||
                        rejection_reason || '|' ||
                        revision || '|' ||
                        state_value || '|' ||
                        response_value
                    FROM fencing_idempotency
                    WHERE idempotency_key = '%s';
                    """.formatted(sql(idempotencyKey.value())));
            if (row.isBlank()) {
                return Optional.empty();
            }
            String[] fields = row.split("\\|", -1);
            Revision revision = new Revision(Long.parseLong(fields[3]));
            FencingState state = new FencingState(fields[4]);
            AuthorityDecision<FencingState, String> decision;
            if (AuthorityDecisionStatus.REJECTED.name().equals(fields[1])) {
                AuthorityRejectionReason reason = AuthorityRejectionReason.valueOf(fields[2]);
                decision = AuthorityDecision.rejected(
                        reason,
                        revision,
                        state,
                        fields[5],
                        traceEnvelope("trace-stored-fencing"));
            } else {
                decision = AuthorityDecision.accepted(
                        revision,
                        state,
                        fields[5],
                        List.of(),
                        traceEnvelope("trace-stored-fencing"));
            }
            return Optional.of(new StoredAuthorityDecision<>(fields[0], decision));
        }

        @Override
        public void store(
                IdempotencyKey idempotencyKey,
                String payloadFingerprint,
                AuthorityDecision<FencingState, String> decision) {
            stack.executePostgres("""
                    INSERT INTO fencing_idempotency (
                        idempotency_key,
                        payload_fingerprint,
                        status,
                        rejection_reason,
                        revision,
                        state_value,
                        response_value
                    ) VALUES (
                        '%s',
                        '%s',
                        '%s',
                        '%s',
                        %d,
                        '%s',
                        '%s'
                    )
                    ON CONFLICT (idempotency_key) DO NOTHING;
                    """.formatted(
                    sql(idempotencyKey.value()),
                    sql(payloadFingerprint),
                    sql(decision.status().name()),
                    sql(decision.rejectionReason().map(Enum::name).orElse("")),
                    decision.revision().value(),
                    sql(decision.state().value()),
                    sql(decision.response())));
        }
    }

    private record SetFencingValue(String value) implements CommandPayload {
    }

    private record FencingState(String value) {
        private String wireValue(Revision revision) {
            return "value=" + value + "\nrevision=" + revision.value();
        }
    }

    private record FencingCommandRecord(
            String commandId,
            String idempotencyKey,
            String declaredPrincipalId,
            String authenticatedPrincipalId,
            String aggregateId,
            String value,
            long expectedRevision,
            String payloadFingerprint) {
        private static FencingCommandRecord fixture() {
            return new FencingCommandRecord(
                    "command-fencing-1",
                    "idempotency-fencing-1",
                    "principal-authority-owner",
                    "principal-authority-owner",
                    "aggregate-fencing-1",
                    "ready",
                    0,
                    "set-fencing-value:ready");
        }

        private String encode() {
            return "commandId=" + commandId
                    + "\nidempotencyKey=" + idempotencyKey
                    + "\ndeclaredPrincipalId=" + declaredPrincipalId
                    + "\nauthenticatedPrincipalId=" + authenticatedPrincipalId
                    + "\naggregateId=" + aggregateId
                    + "\nvalue=" + value
                    + "\nexpectedRevision=" + expectedRevision
                    + "\npayloadFingerprint=" + payloadFingerprint;
        }

        private static FencingCommandRecord decode(String value) {
            Map<String, String> fields = value.lines()
                    .map(line -> line.split("=", 2))
                    .collect(java.util.stream.Collectors.toMap(parts -> parts[0], parts -> parts[1]));
            return new FencingCommandRecord(
                    fields.get("commandId"),
                    fields.get("idempotencyKey"),
                    fields.get("declaredPrincipalId"),
                    fields.get("authenticatedPrincipalId"),
                    fields.get("aggregateId"),
                    fields.get("value"),
                    Long.parseLong(fields.get("expectedRevision")),
                    fields.get("payloadFingerprint"));
        }

        private AuthorityCommand<SetFencingValue> toAuthorityCommand(long fencingEpoch) {
            CommandEnvelope<SetFencingValue> envelope = new CommandEnvelope<>(
                    new CommandId(commandId),
                    new IdempotencyKey(idempotencyKey),
                    new PrincipalId(declaredPrincipalId),
                    new AggregateId(aggregateId),
                    new ContractName("authority-fencing-proof"),
                    new CommandName("set-fencing-value"),
                    traceEnvelope("trace-fencing-command"),
                    Optional.empty(),
                    new SetFencingValue(value));
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
