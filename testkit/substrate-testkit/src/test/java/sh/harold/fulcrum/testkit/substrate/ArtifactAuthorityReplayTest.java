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
import sh.harold.fulcrum.data.artifact.ArtifactDigest;
import sh.harold.fulcrum.data.artifact.ArtifactKind;
import sh.harold.fulcrum.data.artifact.ArtifactMetadata;
import sh.harold.fulcrum.data.artifact.ArtifactMetadataAuthority;
import sh.harold.fulcrum.data.artifact.ArtifactMetadataReceipt;
import sh.harold.fulcrum.data.artifact.ArtifactMetadataReceiptStatus;
import sh.harold.fulcrum.data.artifact.ArtifactMetadataState;
import sh.harold.fulcrum.data.artifact.ContentAddress;
import sh.harold.fulcrum.data.artifact.ProvenanceRef;
import sh.harold.fulcrum.data.artifact.PublishArtifactMetadata;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.AuthorityEmission;
import sh.harold.fulcrum.data.authority.AuthorityEmissionKind;
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

final class ArtifactAuthorityReplayTest {
    private static final String COMMAND_TOPIC = "cmd.artifact-metadata";
    private static final String EVENT_TOPIC = "evt.artifact-metadata";
    private static final String STATE_TOPIC = "state.artifact-metadata";
    private static final String RESPONSE_TOPIC = "rsp.artifact-metadata";
    private static final String AUTHORITY_GROUP = "artifact-authority-replay";
    private static final Instant REQUESTED_AT = Instant.parse("2026-06-16T08:00:00Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-06-16T08:00:01Z");

    @Test
    void uncommittedArtifactMetadataCommandRedeliveryReturnsStoredResult() throws Exception {
        try (FulcrumSubstrateStack stack = FulcrumSubstrateStack.create().start()) {
            createTopics(stack.kafkaBootstrapServers());
            createSchema(stack);

            ArtifactCommandRecord command = ArtifactCommandRecord.fixture();
            sendCommand(stack.kafkaBootstrapServers(), command);

            ArtifactAuthorityWorker firstOwner = new ArtifactAuthorityWorker(stack, AUTHORITY_GROUP);
            AuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> firstDecision =
                    firstOwner.handleNext("attempt-first-owner", false);

            ArtifactAuthorityWorker secondOwner = new ArtifactAuthorityWorker(stack, AUTHORITY_GROUP);
            AuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> redeliveredDecision =
                    secondOwner.handleNext("attempt-second-owner", true);

            assertFalse(firstDecision.replayed());
            assertTrue(redeliveredDecision.replayed());
            assertEquals(firstDecision.response(), redeliveredDecision.response());
            assertEquals(AuthorityDecisionStatus.ACCEPTED, redeliveredDecision.status());
            assertEquals("1", stack.queryPostgresScalar("""
                    SELECT count(*)
                    FROM artifact_metadata_projection
                    WHERE aggregate_id = '%s';
                    """.formatted(sql(command.aggregateId()))));
            assertEquals("1", stack.queryPostgresScalar("""
                    SELECT revision
                    FROM artifact_metadata_projection
                    WHERE aggregate_id = '%s';
                    """.formatted(sql(command.aggregateId()))));
            assertEquals("1", stack.queryPostgresScalar("""
                    SELECT count(*)
                    FROM artifact_authority_idempotency
                    WHERE idempotency_key = '%s';
                    """.formatted(sql(command.idempotencyKey()))));
            assertEquals("false,true", stack.queryPostgresScalar("""
                    SELECT string_agg(replayed, ',' ORDER BY attempt_id)
                    FROM artifact_authority_attempt
                    WHERE idempotency_key = '%s';
                    """.formatted(sql(command.idempotencyKey()))));

            List<String> events = drainTopic(stack.kafkaBootstrapServers(), EVENT_TOPIC, 1);
            List<String> states = drainTopic(stack.kafkaBootstrapServers(), STATE_TOPIC, 1);
            List<String> responses = drainTopic(stack.kafkaBootstrapServers(), RESPONSE_TOPIC, 2);

            assertEquals(1, events.size(), "redelivery must not duplicate the accepted domain event");
            assertEquals(1, states.size(), "redelivery must not duplicate the compacted state emission");
            assertEquals(2, responses.size(), "each delivery returns a command result to the sync path");
            assertEquals(responses.get(0), responses.get(1));
            assertEquals(firstDecision.emissions().stream()
                    .filter(emission -> emission.kind() == AuthorityEmissionKind.CACHE_WRITE)
                    .findFirst()
                    .orElseThrow()
                    .payload(), stack.getValkey("artifact-metadata:" + command.aggregateId()));
        }
    }

    @Test
    void rejectedArtifactMetadataCommandsDoNotWriteProjectionEventsOrCache() throws Exception {
        try (FulcrumSubstrateStack stack = FulcrumSubstrateStack.create().start()) {
            createTopics(stack.kafkaBootstrapServers());
            createSchema(stack);

            long currentFencingEpoch = 7;
            List<ArtifactCommandRecord> commands = List.of(
                    ArtifactCommandRecord.rejectionFixture(
                            "stale-fencing",
                            '1',
                            currentFencingEpoch - 1,
                            0,
                            "principal-artifact-pipeline",
                            "principal-artifact-pipeline"),
                    ArtifactCommandRecord.rejectionFixture(
                            "revision-mismatch",
                            '2',
                            currentFencingEpoch,
                            1,
                            "principal-artifact-pipeline",
                            "principal-artifact-pipeline"),
                    ArtifactCommandRecord.rejectionFixture(
                            "principal-mismatch",
                            '3',
                            currentFencingEpoch,
                            0,
                            "principal-artifact-pipeline",
                            "principal-other-transport"));

            ArtifactAuthorityWorker worker = new ArtifactAuthorityWorker(
                    stack,
                    AUTHORITY_GROUP + "-rejection",
                    currentFencingEpoch);
            sendCommand(stack.kafkaBootstrapServers(), commands.get(0));
            AuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> staleFencing =
                    worker.handleNext("attempt-stale-fencing", true);
            sendCommand(stack.kafkaBootstrapServers(), commands.get(1));
            AuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> revisionMismatch =
                    worker.handleNext("attempt-revision-mismatch", true);
            sendCommand(stack.kafkaBootstrapServers(), commands.get(2));
            AuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> principalMismatch =
                    worker.handleNext("attempt-principal-mismatch", true);

            assertRejected(staleFencing, AuthorityRejectionReason.STALE_FENCING_EPOCH);
            assertRejected(revisionMismatch, AuthorityRejectionReason.REVISION_MISMATCH);
            assertRejected(principalMismatch, AuthorityRejectionReason.PRINCIPAL_MISMATCH);
            assertEquals("0", stack.queryPostgresScalar("SELECT count(*) FROM artifact_metadata_projection;"));
            assertEquals("3", stack.queryPostgresScalar("SELECT count(*) FROM artifact_authority_idempotency;"));
            assertEquals("""
                    command-artifact-metadata-principal-mismatch:PRINCIPAL_MISMATCH:7:7:0:0:principal-artifact-pipeline:principal-other-transport,\
                    command-artifact-metadata-revision-mismatch:REVISION_MISMATCH:7:7:1:0:principal-artifact-pipeline:principal-artifact-pipeline,\
                    command-artifact-metadata-stale-fencing:STALE_FENCING_EPOCH:6:7:0:0:principal-artifact-pipeline:principal-artifact-pipeline\
                    """, stack.queryPostgresScalar("""
                    SELECT string_agg(
                        command_id || ':' ||
                        rejection_reason || ':' ||
                        command_fencing_epoch || ':' ||
                        observed_fencing_epoch || ':' ||
                        expected_revision || ':' ||
                        observed_revision || ':' ||
                        declared_principal || ':' ||
                        authenticated_principal,
                        ',' ORDER BY command_id)
                    FROM artifact_authority_rejection;
                    """));

            List<String> events = drainTopic(stack.kafkaBootstrapServers(), EVENT_TOPIC, 0);
            List<String> states = drainTopic(stack.kafkaBootstrapServers(), STATE_TOPIC, 0);
            List<String> responses = drainTopic(stack.kafkaBootstrapServers(), RESPONSE_TOPIC, 3);

            assertEquals(0, events.size(), "rejected commands must not emit accepted domain events");
            assertEquals(0, states.size(), "rejected commands must not emit compacted state");
            assertEquals(3, responses.size(), "sync path still returns one rejection receipt per command");
            assertTrue(responses.stream().anyMatch(response -> response.contains("reason=STALE_FENCING_EPOCH")));
            assertTrue(responses.stream().anyMatch(response -> response.contains("reason=REVISION_MISMATCH")));
            assertTrue(responses.stream().anyMatch(response -> response.contains("reason=PRINCIPAL_MISMATCH")));
            for (ArtifactCommandRecord command : commands) {
                assertEquals("", stack.getValkey("artifact-metadata:" + command.aggregateId()));
            }
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
                CREATE TABLE artifact_metadata_projection (
                    aggregate_id TEXT PRIMARY KEY,
                    digest_algorithm TEXT NOT NULL,
                    digest_value TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    byte_length BIGINT NOT NULL,
                    content_address TEXT NOT NULL,
                    producer_principal TEXT NOT NULL,
                    provenance TEXT NOT NULL,
                    published_at TEXT NOT NULL,
                    revision BIGINT NOT NULL
                );
                CREATE TABLE artifact_authority_idempotency (
                    idempotency_key TEXT PRIMARY KEY,
                    payload_fingerprint TEXT NOT NULL,
                    status TEXT NOT NULL,
                    rejection_reason TEXT NOT NULL,
                    aggregate_id TEXT NOT NULL,
                    digest_algorithm TEXT NOT NULL,
                    digest_value TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    byte_length BIGINT NOT NULL,
                    content_address TEXT NOT NULL,
                    producer_principal TEXT NOT NULL,
                    provenance TEXT NOT NULL,
                    published_at TEXT NOT NULL,
                    revision BIGINT NOT NULL,
                    fencing_epoch BIGINT NOT NULL,
                    command_id TEXT NOT NULL
                );
                CREATE TABLE artifact_authority_rejection (
                    command_id TEXT PRIMARY KEY,
                    idempotency_key TEXT NOT NULL,
                    aggregate_id TEXT NOT NULL,
                    declared_principal TEXT NOT NULL,
                    authenticated_principal TEXT NOT NULL,
                    command_fencing_epoch BIGINT NOT NULL,
                    observed_fencing_epoch BIGINT NOT NULL,
                    expected_revision BIGINT NOT NULL,
                    observed_revision BIGINT NOT NULL,
                    kafka_offset BIGINT NOT NULL,
                    rejection_reason TEXT NOT NULL
                );
                CREATE TABLE artifact_authority_attempt (
                    attempt_id TEXT PRIMARY KEY,
                    idempotency_key TEXT NOT NULL,
                    replayed TEXT NOT NULL,
                    revision BIGINT NOT NULL
                );
                """);
    }

    private static void sendCommand(String bootstrapServers, ArtifactCommandRecord command) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties(bootstrapServers))) {
            producer.send(new ProducerRecord<>(COMMAND_TOPIC, command.aggregateId(), command.encode())).get(20, TimeUnit.SECONDS);
        }
    }

    private static List<String> drainTopic(String bootstrapServers, String topic, int expectedMinimum) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties(bootstrapServers, topic + "-assertion"))) {
            consumer.subscribe(List.of(topic));
            List<String> values = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            long quietAfter = System.nanoTime() + Duration.ofMillis(750).toNanos();
            while (System.nanoTime() < deadline && (values.size() < expectedMinimum || System.nanoTime() < quietAfter)) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(250));
                if (records.isEmpty()) {
                    continue;
                }
                for (ConsumerRecord<String, String> record : records) {
                    values.add(record.value());
                }
                quietAfter = System.nanoTime() + Duration.ofMillis(750).toNanos();
            }
            return values;
        }
    }

    private static ConsumerRecord<String, String> pollOne(KafkaConsumer<String, String> consumer, String topic) {
        consumer.subscribe(List.of(topic));
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(250));
            for (ConsumerRecord<String, String> record : records) {
                return record;
            }
        }
        throw new AssertionError("Timed out waiting for a Kafka record on " + topic);
    }

    private static Map<String, Object> producerProperties(String bootstrapServers) {
        return Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all");
    }

    private static Map<String, Object> consumerProperties(String bootstrapServers, String groupPrefix) {
        return Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, groupPrefix + "-" + UUID.randomUUID(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    }

    private static Map<String, Object> fixedGroupConsumerProperties(String bootstrapServers, String groupId) {
        return Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, groupId,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    }

    private static String sql(String value) {
        return value.replace("'", "''");
    }

    private static void assertRejected(
            AuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> decision,
            AuthorityRejectionReason reason) {
        assertFalse(decision.replayed());
        assertEquals(AuthorityDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(reason), decision.rejectionReason());
        assertEquals(ArtifactMetadataReceiptStatus.REJECTED, decision.response().status());
        assertFalse(decision.state().metadata().isPresent());
    }

    private static final class ArtifactAuthorityWorker {
        private final FulcrumSubstrateStack stack;
        private final String groupId;
        private final long currentFencingEpoch;

        private ArtifactAuthorityWorker(FulcrumSubstrateStack stack, String groupId) {
            this(stack, groupId, 7);
        }

        private ArtifactAuthorityWorker(FulcrumSubstrateStack stack, String groupId, long currentFencingEpoch) {
            this.stack = stack;
            this.groupId = groupId;
            this.currentFencingEpoch = currentFencingEpoch;
        }

        private AuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> handleNext(
                String attemptId,
                boolean commitOffset) throws Exception {
            BufferedArtifactIdempotencyLedger ledger = new BufferedArtifactIdempotencyLedger(stack);
            ArtifactMetadataAuthority authority = new ArtifactMetadataAuthority(ledger);
            try (KafkaConsumer<String, String> consumer =
                         new KafkaConsumer<>(fixedGroupConsumerProperties(stack.kafkaBootstrapServers(), groupId));
                 KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties(stack.kafkaBootstrapServers()))) {
                ConsumerRecord<String, String> record = pollOne(consumer, COMMAND_TOPIC);
                ArtifactCommandRecord commandRecord = ArtifactCommandRecord.decode(record.value());
                AuthorityCommand<PublishArtifactMetadata> command = commandRecord.toAuthorityCommand();
                AuthorityRecord<ArtifactMetadataState> currentRecord = loadCurrentRecord(commandRecord);
                AuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> decision = authority.handle(command, currentRecord);

                if (decision.replayed()) {
                    storeAttempt(attemptId, commandRecord.idempotencyKey(), decision);
                    publishResponse(producer, commandRecord.aggregateId(), decision.response());
                } else if (decision.status() == AuthorityDecisionStatus.REJECTED) {
                    persistRejectedDecision(attemptId, commandRecord, record.offset(), currentRecord, decision, ledger.pendingDecision());
                    publishResponse(producer, commandRecord.aggregateId(), decision.response());
                } else {
                    persistAcceptedDecision(attemptId, commandRecord, decision, ledger.pendingDecision());
                    publishEmissions(producer, decision.emissions());
                    storeCacheWrites(decision.emissions());
                }
                afterDurableDecisionBeforeOffsetCommit();
                if (commitOffset) {
                    consumer.commitSync();
                }
                return decision;
            }
        }

        private AuthorityRecord<ArtifactMetadataState> loadCurrentRecord(ArtifactCommandRecord command) {
            String row = stack.queryPostgresScalar("""
                    SELECT concat_ws('|',
                        digest_algorithm,
                        digest_value,
                        kind,
                        byte_length,
                        content_address,
                        producer_principal,
                        provenance,
                        published_at,
                        revision)
                    FROM artifact_metadata_projection
                    WHERE aggregate_id = '%s';
                    """.formatted(sql(command.aggregateId())));
            if (row.isBlank()) {
                return ArtifactMetadataAuthority.emptyRecord(currentFencingEpoch);
            }
            String[] fields = row.split("\\|", -1);
            ArtifactMetadata metadata = new ArtifactMetadata(
                    new ArtifactDigest(fields[0], fields[1]),
                    ArtifactKind.valueOf(fields[2]),
                    Long.parseLong(fields[3]),
                    new ContentAddress(fields[4]),
                    new PrincipalId(fields[5]),
                    new ProvenanceRef(fields[6]),
                    Instant.parse(fields[7]));
            return new AuthorityRecord<>(
                    new Revision(Long.parseLong(fields[8])),
                    command.fencingEpoch(),
                    new ArtifactMetadataState(metadata));
        }

        private void persistAcceptedDecision(
                String attemptId,
                ArtifactCommandRecord command,
                AuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> decision,
                StoredAuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> storedDecision) {
            ArtifactMetadata metadata = decision.state().metadata().orElseThrow();
            stack.executePostgres("""
                    BEGIN;
                    INSERT INTO artifact_metadata_projection (
                        aggregate_id,
                        digest_algorithm,
                        digest_value,
                        kind,
                        byte_length,
                        content_address,
                        producer_principal,
                        provenance,
                        published_at,
                        revision
                    ) VALUES (
                        '%s',
                        '%s',
                        '%s',
                        '%s',
                        %d,
                        '%s',
                        '%s',
                        '%s',
                        '%s',
                        %d
                    ) ON CONFLICT (aggregate_id) DO NOTHING;
                    INSERT INTO artifact_authority_idempotency (
                        idempotency_key,
                        payload_fingerprint,
                        status,
                        rejection_reason,
                        aggregate_id,
                        digest_algorithm,
                        digest_value,
                        kind,
                        byte_length,
                        content_address,
                        producer_principal,
                        provenance,
                        published_at,
                        revision,
                        fencing_epoch,
                        command_id
                    ) VALUES (
                        '%s',
                        '%s',
                        '%s',
                        '',
                        '%s',
                        '%s',
                        '%s',
                        '%s',
                        %d,
                        '%s',
                        '%s',
                        '%s',
                        '%s',
                        %d,
                        %d,
                        '%s'
                    ) ON CONFLICT (idempotency_key) DO NOTHING;
                    INSERT INTO artifact_authority_attempt (attempt_id, idempotency_key, replayed, revision)
                    VALUES ('%s', '%s', 'false', %d);
                    COMMIT;
                    """.formatted(
                    sql(command.aggregateId()),
                    sql(metadata.digest().algorithm()),
                    sql(metadata.digest().value()),
                    sql(metadata.kind().name()),
                    metadata.byteLength(),
                    sql(metadata.contentAddress().value()),
                    sql(metadata.producerPrincipal().value()),
                    sql(metadata.provenance().value()),
                    sql(metadata.publishedAt().toString()),
                    decision.revision().value(),
                    sql(command.idempotencyKey()),
                    sql(storedDecision.payloadFingerprint()),
                    sql(storedDecision.decision().status().name()),
                    sql(command.aggregateId()),
                    sql(metadata.digest().algorithm()),
                    sql(metadata.digest().value()),
                    sql(metadata.kind().name()),
                    metadata.byteLength(),
                    sql(metadata.contentAddress().value()),
                    sql(metadata.producerPrincipal().value()),
                    sql(metadata.provenance().value()),
                    sql(metadata.publishedAt().toString()),
                    decision.revision().value(),
                    command.fencingEpoch(),
                    sql(command.commandId()),
                    sql(attemptId),
                    sql(command.idempotencyKey()),
                    decision.revision().value()));
        }

        private void persistRejectedDecision(
                String attemptId,
                ArtifactCommandRecord command,
                long kafkaOffset,
                AuthorityRecord<ArtifactMetadataState> currentRecord,
                AuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> decision,
                StoredAuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> storedDecision) {
            AuthorityRejectionReason reason = decision.rejectionReason().orElseThrow();
            stack.executePostgres("""
                    BEGIN;
                    INSERT INTO artifact_authority_idempotency (
                        idempotency_key,
                        payload_fingerprint,
                        status,
                        rejection_reason,
                        aggregate_id,
                        digest_algorithm,
                        digest_value,
                        kind,
                        byte_length,
                        content_address,
                        producer_principal,
                        provenance,
                        published_at,
                        revision,
                        fencing_epoch,
                        command_id
                    ) VALUES (
                        '%s',
                        '%s',
                        '%s',
                        '%s',
                        '%s',
                        '%s',
                        '%s',
                        '%s',
                        %d,
                        '%s',
                        '%s',
                        '%s',
                        '%s',
                        %d,
                        %d,
                        '%s'
                    ) ON CONFLICT (idempotency_key) DO NOTHING;
                    INSERT INTO artifact_authority_rejection (
                        command_id,
                        idempotency_key,
                        aggregate_id,
                        declared_principal,
                        authenticated_principal,
                        command_fencing_epoch,
                        observed_fencing_epoch,
                        expected_revision,
                        observed_revision,
                        kafka_offset,
                        rejection_reason
                    ) VALUES (
                        '%s',
                        '%s',
                        '%s',
                        '%s',
                        '%s',
                        %d,
                        %d,
                        %d,
                        %d,
                        %d,
                        '%s'
                    );
                    INSERT INTO artifact_authority_attempt (attempt_id, idempotency_key, replayed, revision)
                    VALUES ('%s', '%s', 'false', %d);
                    COMMIT;
                    """.formatted(
                    sql(command.idempotencyKey()),
                    sql(storedDecision.payloadFingerprint()),
                    sql(storedDecision.decision().status().name()),
                    sql(reason.name()),
                    sql(command.aggregateId()),
                    sql(command.digestAlgorithm()),
                    sql(command.digestValue()),
                    sql(command.kind()),
                    command.byteLength(),
                    sql(command.contentAddress()),
                    sql(command.declaredPrincipalId()),
                    sql(command.provenance()),
                    sql(RECEIVED_AT.toString()),
                    decision.revision().value(),
                    command.fencingEpoch(),
                    sql(command.commandId()),
                    sql(command.commandId()),
                    sql(command.idempotencyKey()),
                    sql(command.aggregateId()),
                    sql(command.declaredPrincipalId()),
                    sql(command.authenticatedPrincipalId()),
                    command.fencingEpoch(),
                    currentRecord.fencingEpoch(),
                    command.expectedRevision(),
                    currentRecord.revision().value(),
                    kafkaOffset,
                    sql(reason.name()),
                    sql(attemptId),
                    sql(command.idempotencyKey()),
                    decision.revision().value()));
        }

        private void storeAttempt(
                String attemptId,
                String idempotencyKey,
                AuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> decision) {
            stack.executePostgres("""
                    INSERT INTO artifact_authority_attempt (attempt_id, idempotency_key, replayed, revision)
                    VALUES ('%s', '%s', '%s', %d);
                    """.formatted(
                    sql(attemptId),
                    sql(idempotencyKey),
                    Boolean.toString(decision.replayed()),
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

        private void publishResponse(
                KafkaProducer<String, String> producer,
                String aggregateId,
                ArtifactMetadataReceipt receipt) throws Exception {
            producer.send(new ProducerRecord<>(
                    RESPONSE_TOPIC,
                    receipt.commandId().orElse(aggregateId),
                    receiptValue(receipt))).get(20, TimeUnit.SECONDS);
        }

        private void storeCacheWrites(List<AuthorityEmission> emissions) {
            emissions.stream()
                    .filter(emission -> emission.kind() == AuthorityEmissionKind.CACHE_WRITE)
                    .forEach(emission -> stack.setValkey(emission.key(), emission.payload()));
        }

        private void afterDurableDecisionBeforeOffsetCommit() {
            // Named seam for the follow-up crash-before-offset-commit proof.
        }
    }

    private static final class BufferedArtifactIdempotencyLedger
            implements IdempotencyLedger<ArtifactMetadataState, ArtifactMetadataReceipt> {
        private final FulcrumSubstrateStack stack;
        private StoredAuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> pendingDecision;

        private BufferedArtifactIdempotencyLedger(FulcrumSubstrateStack stack) {
            this.stack = stack;
        }

        @Override
        public Optional<StoredAuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt>> find(IdempotencyKey idempotencyKey) {
            String row = stack.queryPostgresScalar("""
                    SELECT concat_ws('|',
                        payload_fingerprint,
                        status,
                        rejection_reason,
                        aggregate_id,
                        digest_algorithm,
                        digest_value,
                        kind,
                        byte_length,
                        content_address,
                        producer_principal,
                        provenance,
                        published_at,
                        revision,
                        fencing_epoch,
                        command_id)
                    FROM artifact_authority_idempotency
                    WHERE idempotency_key = '%s';
                    """.formatted(sql(idempotencyKey.value())));
            if (row.isBlank()) {
                return Optional.empty();
            }
            String[] fields = row.split("\\|", -1);
            Revision revision = new Revision(Long.parseLong(fields[12]));
            if (AuthorityDecisionStatus.REJECTED.name().equals(fields[1])) {
                AuthorityRejectionReason reason = AuthorityRejectionReason.valueOf(fields[2]);
                ArtifactMetadataReceipt receipt = new ArtifactMetadataReceipt(
                        ArtifactMetadataReceiptStatus.REJECTED,
                        Optional.of(reason.name()),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty());
                AuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> decision = AuthorityDecision.rejected(
                        reason,
                        revision,
                        new ArtifactMetadataState(Optional.empty()),
                        receipt,
                        traceEnvelope("trace-stored-decision"));
                return Optional.of(new StoredAuthorityDecision<>(fields[0], decision));
            }
            ArtifactDigest digest = new ArtifactDigest(fields[4], fields[5]);
            ArtifactMetadata metadata = new ArtifactMetadata(
                    digest,
                    ArtifactKind.valueOf(fields[6]),
                    Long.parseLong(fields[7]),
                    new ContentAddress(fields[8]),
                    new PrincipalId(fields[9]),
                    new ProvenanceRef(fields[10]),
                    Instant.parse(fields[11]));
            ArtifactMetadataReceipt receipt = new ArtifactMetadataReceipt(
                    ArtifactMetadataReceiptStatus.ACCEPTED,
                    Optional.empty(),
                    Optional.of(digest),
                    Optional.of(revision),
                    Optional.of(Long.parseLong(fields[13])),
                    Optional.of(idempotencyKey.value()),
                    Optional.of(fields[14]));
            AuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> decision = AuthorityDecision.accepted(
                    revision,
                    new ArtifactMetadataState(metadata),
                    receipt,
                    List.of(),
                    traceEnvelope("trace-stored-decision"));
            return Optional.of(new StoredAuthorityDecision<>(fields[0], decision));
        }

        @Override
        public void store(
                IdempotencyKey idempotencyKey,
                String payloadFingerprint,
                AuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> decision) {
            pendingDecision = new StoredAuthorityDecision<>(payloadFingerprint, decision);
        }

        private StoredAuthorityDecision<ArtifactMetadataState, ArtifactMetadataReceipt> pendingDecision() {
            if (pendingDecision == null) {
                throw new IllegalStateException("Authority accepted a non-replay command without storing idempotency state");
            }
            return pendingDecision;
        }
    }

    private static String receiptValue(ArtifactMetadataReceipt receipt) {
        return "status=" + receipt.status().name()
                + "\nreason=" + receipt.rejectionReason().orElse("")
                + "\ndigest=" + receipt.digest().map(ArtifactAuthorityReplayTest::aggregateId).orElse("")
                + "\nrevision=" + receipt.revision().map(value -> Long.toString(value.value())).orElse("")
                + "\nfencingEpoch=" + receipt.fencingEpoch().map(Object::toString).orElse("")
                + "\nidempotencyKey=" + receipt.idempotencyKey().orElse("")
                + "\ncommandId=" + receipt.commandId().orElse("");
    }

    private static String aggregateId(ArtifactDigest digest) {
        return ArtifactMetadataAuthority.aggregateId(digest).value();
    }

    private static TraceEnvelope traceEnvelope(String traceId) {
        return new TraceEnvelope(
                traceId,
                "span-artifact-authority",
                Optional.empty(),
                REQUESTED_AT,
                "artifact-authority-test",
                new InstanceId("instance-artifact-authority-test"));
    }

    private record ArtifactCommandRecord(
            String commandId,
            String idempotencyKey,
            String declaredPrincipalId,
            String authenticatedPrincipalId,
            String aggregateId,
            String digestAlgorithm,
            String digestValue,
            String kind,
            long byteLength,
            String contentAddress,
            String provenance,
            long fencingEpoch,
            long expectedRevision,
            String payloadFingerprint) {
        private static ArtifactCommandRecord fixture() {
            ArtifactDigest digest = new ArtifactDigest(
                    "sha-256",
                    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
            return new ArtifactCommandRecord(
                    "command-artifact-metadata-1",
                    "idempotency-artifact-metadata-1",
                    "principal-artifact-pipeline",
                    "principal-artifact-pipeline",
                    ArtifactAuthorityReplayTest.aggregateId(digest),
                    digest.algorithm(),
                    digest.value(),
                    ArtifactKind.CAPABILITY_BUNDLE.name(),
                    4096,
                    "object://artifact-store/capability/test",
                    "build:artifact-pipeline:42",
                    7,
                    0,
                    "publish-artifact-metadata:0123456789abcdef");
        }

        private static ArtifactCommandRecord rejectionFixture(
                String suffix,
                char digestNibble,
                long fencingEpoch,
                long expectedRevision,
                String declaredPrincipalId,
                String authenticatedPrincipalId) {
            ArtifactDigest digest = new ArtifactDigest("sha-256", Character.toString(digestNibble).repeat(64));
            return new ArtifactCommandRecord(
                    "command-artifact-metadata-" + suffix,
                    "idempotency-artifact-metadata-" + suffix,
                    declaredPrincipalId,
                    authenticatedPrincipalId,
                    ArtifactAuthorityReplayTest.aggregateId(digest),
                    digest.algorithm(),
                    digest.value(),
                    ArtifactKind.CAPABILITY_BUNDLE.name(),
                    4096,
                    "object://artifact-store/capability/" + suffix,
                    "build:artifact-pipeline:" + suffix,
                    fencingEpoch,
                    expectedRevision,
                    "publish-artifact-metadata:" + suffix);
        }

        private String encode() {
            return "commandId=" + commandId
                    + "\nidempotencyKey=" + idempotencyKey
                    + "\ndeclaredPrincipalId=" + declaredPrincipalId
                    + "\nauthenticatedPrincipalId=" + authenticatedPrincipalId
                    + "\naggregateId=" + aggregateId
                    + "\ndigestAlgorithm=" + digestAlgorithm
                    + "\ndigestValue=" + digestValue
                    + "\nkind=" + kind
                    + "\nbyteLength=" + byteLength
                    + "\ncontentAddress=" + contentAddress
                    + "\nprovenance=" + provenance
                    + "\nfencingEpoch=" + fencingEpoch
                    + "\nexpectedRevision=" + expectedRevision
                    + "\npayloadFingerprint=" + payloadFingerprint;
        }

        private static ArtifactCommandRecord decode(String value) {
            Map<String, String> fields = value.lines()
                    .map(line -> line.split("=", 2))
                    .collect(java.util.stream.Collectors.toMap(parts -> parts[0], parts -> parts[1]));
            return new ArtifactCommandRecord(
                    fields.get("commandId"),
                    fields.get("idempotencyKey"),
                    fields.get("declaredPrincipalId"),
                    fields.get("authenticatedPrincipalId"),
                    fields.get("aggregateId"),
                    fields.get("digestAlgorithm"),
                    fields.get("digestValue"),
                    fields.get("kind"),
                    Long.parseLong(fields.get("byteLength")),
                    fields.get("contentAddress"),
                    fields.get("provenance"),
                    Long.parseLong(fields.get("fencingEpoch")),
                    Long.parseLong(fields.get("expectedRevision")),
                    fields.get("payloadFingerprint"));
        }

        private AuthorityCommand<PublishArtifactMetadata> toAuthorityCommand() {
            ArtifactDigest digest = new ArtifactDigest(digestAlgorithm, digestValue);
            PublishArtifactMetadata payload = new PublishArtifactMetadata(
                    digest,
                    ArtifactKind.valueOf(kind),
                    byteLength,
                    new ContentAddress(contentAddress),
                    new ProvenanceRef(provenance));
            CommandEnvelope<PublishArtifactMetadata> envelope = new CommandEnvelope<>(
                    new CommandId(commandId),
                    new IdempotencyKey(idempotencyKey),
                    new PrincipalId(declaredPrincipalId),
                    new AggregateId(aggregateId),
                    new ContractName("artifact-metadata"),
                    new CommandName("publish-artifact-metadata"),
                    traceEnvelope("trace-artifact-command"),
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
