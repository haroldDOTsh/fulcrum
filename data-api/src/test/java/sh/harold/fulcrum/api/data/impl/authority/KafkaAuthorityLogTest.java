package sh.harold.fulcrum.api.data.impl.authority;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KafkaAuthorityLogTest {
    @Test
    void appendProducesKeyedRecordToExplicitAuthorityPartition() {
        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaAuthorityLog log = new KafkaAuthorityLog(
            mock(Admin.class),
            producer,
            AuthorityLogTopology.policiesByTopic(),
            Duration.ofSeconds(1)
        );
        AuthorityCommandRoute route = AuthorityCommandRoute.from(
            DataAuthority.CommandType.GRANT_RANK,
            "rank:player:" + UUID.randomUUID()
        );

        AuthorityLogRecord record = log.append(
            route,
            AuthorityLogTopicKind.COMMAND,
            Map.of("declarationId", "GRANT_RANK")
        );

        ProducerRecord<String, String> produced = producer.history().get(0);
        assertThat(produced.topic()).isEqualTo(route.commandTopic());
        assertThat(produced.key()).isEqualTo(route.partitionKey());
        assertThat(produced.partition()).isEqualTo(AuthorityLogTopology.partition(route));
        assertThat(produced.value()).contains("\"declarationId\":\"GRANT_RANK\"");
        assertThat(new String(produced.headers().lastHeader("authority-log-kind").value(), StandardCharsets.UTF_8))
            .isEqualTo("COMMAND");
        assertThat(new String(produced.headers().lastHeader("authority-key-rule").value(), StandardCharsets.UTF_8))
            .isEqualTo("aggregate-id");
        assertThat(record.topic()).isEqualTo(route.commandTopic());
        assertThat(record.key()).isEqualTo(route.partitionKey());
        assertThat(record.offset()).isZero();
        assertThat(record.headers())
            .containsEntry("authority-domain", route.domain())
            .containsEntry("authority-route-manifest-fingerprint",
                DataAuthorityCommandContracts.routeManifestFingerprint());
    }

    @Test
    void topologyViolationsRejectMissingWrongPartitionAndCompactionDrift() {
        AuthorityLogTopicPolicy statePolicy = new AuthorityLogTopicPolicy(
            "state.rank",
            AuthorityLogTopicKind.STATE,
            "rank",
            4,
            true,
            "compacted-forever"
        );
        AuthorityLogTopicPolicy commandPolicy = new AuthorityLogTopicPolicy(
            "cmd.rank",
            AuthorityLogTopicKind.COMMAND,
            "rank",
            4,
            false,
            "retained-days"
        );

        List<String> violations = KafkaAuthorityLog.topologyViolations(
            Map.of(
                statePolicy.topic(), statePolicy,
                commandPolicy.topic(), commandPolicy,
                "rsp.rank", new AuthorityLogTopicPolicy(
                    "rsp.rank",
                    AuthorityLogTopicKind.RESPONSE,
                    "rank",
                    4,
                    false,
                    "retained-hours"
                )
            ),
            Map.of(
                statePolicy.topic(), topic(statePolicy.topic(), 2),
                commandPolicy.topic(), topic(commandPolicy.topic(), 4)
            ),
            Map.of(
                statePolicy.topic(), "delete",
                commandPolicy.topic(), "delete"
            )
        );

        assertThat(violations).containsExactlyInAnyOrder(
            "state.rank partitions=2 expected=4",
            "state.rank compacted=false expected=true",
            "rsp.rank missing"
        );
    }

    @Test
    void kafkaCommandWorkerConsumesAssignedPartitionAndCommitsAfterProcessing() {
        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaAuthorityLog log = new KafkaAuthorityLog(
            mock(Admin.class),
            producer,
            AuthorityLogTopology.policiesByTopic(),
            Duration.ofSeconds(1)
        );
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        DataAuthority.PlayerRankCommand command = rankCommand(7L);
        AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(command);
        AuthorityLogRecord commandRecord = AuthorityLogFrames.appendCommand(log, command);
        ProducerRecord<String, String> produced = producer.history().get(0);
        TopicPartition topicPartition = new TopicPartition(
            route.commandTopic(),
            AuthorityLogTopology.partition(route)
        );
        AtomicReference<DataAuthority.AuthorityCommand> applied = new AtomicReference<>();
        AuthorityLogCommandWorker worker = new AuthorityLogCommandWorker(
            log,
            appliedCommand -> {
                applied.set(appliedCommand);
                return CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                    appliedCommand.commandId(),
                    true,
                    8L,
                    DataAuthority.RejectionReason.NONE,
                    "accepted"
                ));
            },
            (commandDomain, commandTopic, partitionKey, ownerNode) -> AuthorityWriterClaim.mint(
                commandDomain,
                commandTopic,
                partitionKey,
                ownerNode,
                3L,
                null,
                0L,
                Instant.EPOCH
            ),
            "authority-rank-1"
        );
        InMemoryAuthorityCommandConsumerCursorStore cursorStore =
            new InMemoryAuthorityCommandConsumerCursorStore();
        KafkaAuthorityCommandWorker kafkaWorker = new KafkaAuthorityCommandWorker(
            log,
            consumer,
            worker,
            cursorStore,
            "rank"
        );
        consumer.rebalance(List.of(topicPartition));
        consumer.updateBeginningOffsets(Map.of(topicPartition, 0L));
        consumer.addRecord(new ConsumerRecord<>(
            produced.topic(),
            topicPartition.partition(),
            commandRecord.offset(),
            produced.key(),
            produced.value()
        ));

        KafkaAuthorityCommandWorker.PollResult result = kafkaWorker.pollOnce(Duration.ZERO);

        assertThat(result.domain()).isEqualTo("rank");
        assertThat(result.consumerGroup()).isEqualTo("authority-rank");
        assertThat(result.scannedCount()).isEqualTo(1);
        assertThat(result.processedCount()).isEqualTo(1);
        assertThat(applied.get().commandId()).isEqualTo(command.commandId());
        assertThat(result.committedOffsets()).containsEntry(topicPartition, new OffsetAndMetadata(1L));
        assertThat(consumer.committed(topicPartition)).isEqualTo(new OffsetAndMetadata(1L));
        assertThat(cursorStore.cursor("rank", topicPartition.partition())).hasValueSatisfying(cursor -> {
            assertThat(cursor.commandTopic()).isEqualTo(route.commandTopic());
            assertThat(cursor.committedOffset()).isEqualTo(commandRecord.offset());
            assertThat(cursor.lastCommandId()).isEqualTo(command.commandId());
            assertThat(cursor.ownerNode()).isEqualTo("authority-rank-1");
        });
        assertThat(producer.history()).hasSize(2);
        assertThat(producer.history().get(1).topic()).isEqualTo(route.responseTopic());
    }

    @Test
    void kafkaCommandWorkerUsesAssignmentClaimForAssignedPartition() {
        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaAuthorityLog log = new KafkaAuthorityLog(
            mock(Admin.class),
            producer,
            AuthorityLogTopology.policiesByTopic(),
            Duration.ofSeconds(1)
        );
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        DataAuthority.PlayerRankCommand command = rankCommand(17L);
        AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(command);
        AuthorityLogRecord commandRecord = AuthorityLogFrames.appendCommand(log, command);
        ProducerRecord<String, String> produced = producer.history().get(0);
        TopicPartition topicPartition = new TopicPartition(
            route.commandTopic(),
            AuthorityLogTopology.partition(route)
        );
        AtomicInteger claimCalls = new AtomicInteger();
        AtomicReference<AuthorityWriterClaim> assignmentClaim = new AtomicReference<>();
        AtomicReference<DataAuthority.AuthorityCommand> applied = new AtomicReference<>();
        AuthorityFencingCommandPort.PartitionEpochStore epochStore =
            (commandDomain, commandTopic, partitionKey, ownerNode) -> {
                AuthorityWriterClaim claim = AuthorityWriterClaim.mint(
                    commandDomain,
                    commandTopic,
                    partitionKey,
                    ownerNode,
                    40L + claimCalls.incrementAndGet(),
                    null,
                    0L,
                    Instant.EPOCH
                );
                assignmentClaim.set(claim);
                return claim;
            };
        AuthorityLogCommandWorker worker = new AuthorityLogCommandWorker(
            log,
            appliedCommand -> {
                applied.set(appliedCommand);
                return CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                    appliedCommand.commandId(),
                    true,
                    18L,
                    DataAuthority.RejectionReason.NONE,
                    "accepted"
                ));
            },
            epochStore,
            "authority-rank-1"
        );
        KafkaAuthorityCommandWorker kafkaWorker = new KafkaAuthorityCommandWorker(
            log,
            consumer,
            worker,
            AuthorityCommandConsumerCursorStore.inMemory(),
            epochStore,
            "authority-rank-1",
            "rank"
        );
        consumer.rebalance(List.of(topicPartition));
        consumer.updateBeginningOffsets(Map.of(topicPartition, 0L));
        consumer.addRecord(new ConsumerRecord<>(
            produced.topic(),
            topicPartition.partition(),
            commandRecord.offset(),
            produced.key(),
            produced.value()
        ));

        KafkaAuthorityCommandWorker.PollResult result = kafkaWorker.pollOnce(Duration.ZERO);

        assertThat(claimCalls.get()).isEqualTo(1);
        assertThat(result.processedCount()).isEqualTo(1);
        assertThat(applied.get().commandId()).isEqualTo(command.commandId());
        assertThat(applied.get().fencingToken()).isEqualTo(assignmentClaim.get().fencingToken());
        assertThat(assignmentClaim.get().partitionKey()).isEqualTo("rank:lane:" + topicPartition.partition());
        assertThat(assignmentClaim.get().ownerNode()).contains("authority-rank-1:kafka:rank:");
        assertThat(result.partitions()).singleElement().satisfies(partition ->
            assertThat(partition.writerClaim()).isEqualTo(assignmentClaim.get())
        );
        assertThat(consumer.committed(topicPartition)).isEqualTo(new OffsetAndMetadata(1L));
    }

    @Test
    void kafkaCommandWorkerDoesNotCommitOffsetWhenProcessingFails() {
        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaAuthorityLog log = new KafkaAuthorityLog(
            mock(Admin.class),
            producer,
            AuthorityLogTopology.policiesByTopic(),
            Duration.ofSeconds(1)
        );
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        AuthorityDomainTopology.DomainTopology topology = AuthorityDomainTopology.domain("rank");
        TopicPartition topicPartition = new TopicPartition(topology.commandTopic(), 0);
        AuthorityLogCommandWorker worker = new AuthorityLogCommandWorker(
            log,
            ignored -> {
                throw new AssertionError("malformed command must not reach delegate");
            },
            (commandDomain, commandTopic, partitionKey, ownerNode) -> AuthorityWriterClaim.mint(
                commandDomain,
                commandTopic,
                partitionKey,
                ownerNode,
                3L,
                null,
                0L,
                Instant.EPOCH
            ),
            "authority-rank-1"
        );
        KafkaAuthorityCommandWorker kafkaWorker = new KafkaAuthorityCommandWorker(
            log,
            consumer,
            worker,
            "rank"
        );
        consumer.rebalance(List.of(topicPartition));
        consumer.updateBeginningOffsets(Map.of(topicPartition, 0L));
        consumer.addRecord(new ConsumerRecord<>(
            topology.commandTopic(),
            topicPartition.partition(),
            0L,
            "rank:player:" + UUID.randomUUID(),
            "{}"
        ));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> kafkaWorker.pollOnce(Duration.ZERO))
            .hasRootCauseInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("manifest");

        assertThat(consumer.committed(topicPartition)).isNull();
    }

    private static TopicDescription topic(String name, int partitions) {
        return new TopicDescription(
            name,
            false,
            java.util.stream.IntStream.range(0, partitions)
                .mapToObj(partition -> new TopicPartitionInfo(partition, null, List.of(), List.of()))
                .toList()
        );
    }

    private static DataAuthority.PlayerRankCommand rankCommand(long expectedRevision) {
        UUID playerId = UUID.randomUUID();
        DataAuthority.CommandManifest manifest = new DataAuthority.CommandManifest(
            UUID.randomUUID(),
            DataAuthority.CommandType.GRANT_RANK,
            "node:paper-1",
            "rank:player:" + playerId,
            "rank-grant:" + playerId + ":" + expectedRevision,
            System.currentTimeMillis() + 5_000L,
            "",
            expectedRevision,
            DataAuthority.COMMAND_SCHEMA_VERSION,
            new DataAuthority.CommandProvenance(
                "paper-1",
                "authority-rank",
                "kafka",
                DataAuthority.COMMAND_SCHEMA_VERSION,
                "node:paper-1"
            )
        );
        return new DataAuthority.PlayerRankCommand(
            manifest,
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN")
        );
    }
}
