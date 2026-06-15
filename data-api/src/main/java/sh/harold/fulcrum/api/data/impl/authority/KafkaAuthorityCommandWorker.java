package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kafka consumer-group authority worker for one command domain.
 */
public final class KafkaAuthorityCommandWorker implements AutoCloseable {
    private static final Duration DEFAULT_POLL_TIMEOUT = Duration.ofMillis(250);

    private final KafkaAuthorityLog log;
    private final Consumer<String, String> consumer;
    private final AuthorityLogCommandWorker worker;
    private final AuthorityCommandConsumerCursorStore cursorStore;
    private final AuthorityFencingCommandPort.PartitionEpochStore epochStore;
    private final String ownerNode;
    private final AuthorityDomainTopology.DomainTopology topology;
    private final ConcurrentMap<TopicPartition, AuthorityWriterClaim> assignedClaims = new ConcurrentHashMap<>();
    private final String workerInstanceId = UUID.randomUUID().toString();
    private final AtomicLong assignmentGeneration = new AtomicLong();

    public KafkaAuthorityCommandWorker(
        KafkaAuthorityLog log,
        DataAuthority.CommandPort delegate,
        AuthorityFencingCommandPort.PartitionEpochStore epochStore,
        String ownerNode,
        String domain
    ) {
        this(
            log,
            log.commandConsumer(AuthorityDomainTopology.domain(domain).consumerGroup()),
            new AuthorityLogCommandWorker(log, delegate, epochStore, ownerNode),
            AuthorityCommandConsumerCursorStore.inMemory(),
            epochStore,
            ownerNode,
            domain
        );
    }

    public KafkaAuthorityCommandWorker(
        KafkaAuthorityLog log,
        DataAuthority.CommandPort delegate,
        AuthorityFencingCommandPort.PartitionEpochStore epochStore,
        AuthorityCommandConsumerCursorStore cursorStore,
        String ownerNode,
        String domain
    ) {
        this(
            log,
            log.commandConsumer(AuthorityDomainTopology.domain(domain).consumerGroup()),
            new AuthorityLogCommandWorker(log, delegate, epochStore, ownerNode),
            cursorStore,
            epochStore,
            ownerNode,
            domain
        );
    }

    KafkaAuthorityCommandWorker(
        KafkaAuthorityLog log,
        Consumer<String, String> consumer,
        AuthorityLogCommandWorker worker,
        String domain
    ) {
        this(
            log,
            consumer,
            worker,
            AuthorityCommandConsumerCursorStore.inMemory(),
            null,
            null,
            domain
        );
    }

    KafkaAuthorityCommandWorker(
        KafkaAuthorityLog log,
        Consumer<String, String> consumer,
        AuthorityLogCommandWorker worker,
        AuthorityCommandConsumerCursorStore cursorStore,
        String domain
    ) {
        this(
            log,
            consumer,
            worker,
            cursorStore,
            null,
            null,
            domain
        );
    }

    KafkaAuthorityCommandWorker(
        KafkaAuthorityLog log,
        Consumer<String, String> consumer,
        AuthorityLogCommandWorker worker,
        AuthorityCommandConsumerCursorStore cursorStore,
        AuthorityFencingCommandPort.PartitionEpochStore epochStore,
        String ownerNode,
        String domain
    ) {
        this.log = Objects.requireNonNull(log, "log");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.cursorStore = Objects.requireNonNull(cursorStore, "cursorStore");
        this.epochStore = epochStore;
        this.ownerNode = ownerNode == null ? null : ownerNode.trim();
        this.topology = AuthorityDomainTopology.domain(domain);
        if (epochStore != null && (this.ownerNode == null || this.ownerNode.isBlank())) {
            throw new IllegalArgumentException("ownerNode is required when Kafka assignment claims are enabled");
        }
        this.consumer.subscribe(List.of(topology.commandTopic()), rebalanceListener());
    }

    public String domain() {
        return topology.domain();
    }

    public String consumerGroup() {
        return topology.consumerGroup();
    }

    public PollResult pollOnce() {
        return pollOnce(DEFAULT_POLL_TIMEOUT);
    }

    public PollResult pollOnce(Duration timeout) {
        ConsumerRecords<String, String> records = consumer.poll(timeout == null ? DEFAULT_POLL_TIMEOUT : timeout);
        Map<TopicPartition, List<AuthorityLogRecord>> byPartition = recordsByPartition(records);
        Map<TopicPartition, OffsetAndMetadata> offsetsToCommit = new LinkedHashMap<>();
        List<AuthorityLogCommandWorker.PartitionResult> partitionResults = new ArrayList<>();
        int scannedCount = 0;
        int processedCount = 0;

        for (Map.Entry<TopicPartition, List<AuthorityLogRecord>> entry : byPartition.entrySet()) {
            TopicPartition topicPartition = entry.getKey();
            List<AuthorityLogRecord> partitionRecords = entry.getValue();
            scannedCount += partitionRecords.size();
            AuthorityLogCommandWorker.PartitionResult result =
                processPartitionRecords(topicPartition, partitionRecords);
            partitionResults.add(result);
            processedCount += result.processedCount();
            if (result.lastProcessedOffset() >= 0L) {
                cursorStore.recordApplied(result);
                offsetsToCommit.put(
                    topicPartition,
                    new OffsetAndMetadata(result.lastProcessedOffset() + 1L)
                );
            }
        }

        if (!offsetsToCommit.isEmpty()) {
            consumer.commitSync(offsetsToCommit);
        }
        return new PollResult(
            topology.domain(),
            topology.consumerGroup(),
            scannedCount,
            processedCount,
            offsetsToCommit,
            partitionResults
        );
    }

    @Override
    public void close() {
        assignedClaims.clear();
        consumer.close();
    }

    private ConsumerRebalanceListener rebalanceListener() {
        return new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                for (TopicPartition partition : partitions) {
                    if (topology.commandTopic().equals(partition.topic())) {
                        assignedClaims.remove(partition);
                    }
                }
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                if (!assignmentClaimsEnabled()) {
                    return;
                }
                long generation = assignmentGeneration.incrementAndGet();
                for (TopicPartition partition : partitions) {
                    if (topology.commandTopic().equals(partition.topic())) {
                        assignedClaims.put(partition, claimPartition(partition, generation));
                    }
                }
            }
        };
    }

    private AuthorityWriterClaim writerClaim(TopicPartition partition) {
        AuthorityWriterClaim claim = assignedClaims.get(partition);
        if (claim != null) {
            return claim;
        }
        if (!assignmentClaimsEnabled()) {
            return null;
        }
        throw new IllegalStateException("Kafka authority worker has no assignment claim for " + partition);
    }

    private AuthorityWriterClaim claimPartition(TopicPartition partition, long generation) {
        return epochStore.claimEpoch(
            topology.domain(),
            topology.commandTopic(),
            AuthorityLogCommandWorker.partitionFencingScope(topology.domain(), partition.partition()),
            assignmentOwnerNode(generation)
        );
    }

    private String assignmentOwnerNode(long generation) {
        return ownerNode + ":kafka:" + topology.domain() + ":" + workerInstanceId + ":" + generation;
    }

    private boolean assignmentClaimsEnabled() {
        return epochStore != null;
    }

    private AuthorityLogCommandWorker.PartitionResult processPartitionRecords(
        TopicPartition topicPartition,
        List<AuthorityLogRecord> partitionRecords
    ) {
        if (!assignmentClaimsEnabled()) {
            return worker
                .processRecords(topology.domain(), topicPartition.partition(), partitionRecords)
                .toCompletableFuture()
                .join();
        }
        return worker
            .processRecords(
                topology.domain(),
                topicPartition.partition(),
                partitionRecords,
                writerClaim(topicPartition)
            )
            .toCompletableFuture()
            .join();
    }

    private Map<TopicPartition, List<AuthorityLogRecord>> recordsByPartition(
        ConsumerRecords<String, String> records
    ) {
        Map<TopicPartition, List<AuthorityLogRecord>> values = new LinkedHashMap<>();
        records.partitions().stream()
            .filter(partition -> topology.commandTopic().equals(partition.topic()))
            .sorted(Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition))
            .forEach(partition -> {
                List<AuthorityLogRecord> decoded = records.records(partition).stream()
                    .map(this::authorityRecord)
                    .toList();
                if (!decoded.isEmpty()) {
                    values.put(partition, decoded);
                }
            });
        return values;
    }

    private AuthorityLogRecord authorityRecord(ConsumerRecord<String, String> record) {
        AuthorityLogRecord decoded = log.authorityRecord(record);
        if (decoded.kind() != AuthorityLogTopicKind.COMMAND) {
            throw new IllegalArgumentException("Kafka authority command worker expected COMMAND record");
        }
        return decoded;
    }

    public record PollResult(
        String domain,
        String consumerGroup,
        int scannedCount,
        int processedCount,
        Map<TopicPartition, OffsetAndMetadata> committedOffsets,
        List<AuthorityLogCommandWorker.PartitionResult> partitions
    ) {
        public PollResult {
            if (domain == null || domain.isBlank()) {
                throw new IllegalArgumentException("domain is required");
            }
            if (consumerGroup == null || consumerGroup.isBlank()) {
                throw new IllegalArgumentException("consumerGroup is required");
            }
            if (scannedCount < 0) {
                throw new IllegalArgumentException("scannedCount must not be negative");
            }
            if (processedCount < 0 || processedCount > scannedCount) {
                throw new IllegalArgumentException("processedCount must be within scannedCount");
            }
            committedOffsets = committedOffsets == null ? Map.of() : Map.copyOf(committedOffsets);
            partitions = partitions == null ? List.of() : List.copyOf(partitions);
        }
    }
}
