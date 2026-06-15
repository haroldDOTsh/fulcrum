package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Partition-owned authority worker for already-durable command log records.
 */
public final class AuthorityLogCommandWorker {
    private final AuthorityLog log;
    private final AuthorityLogCommandProcessor processor;
    private final AuthorityFencingCommandPort.PartitionEpochStore epochStore;
    private final String ownerNode;

    public AuthorityLogCommandWorker(
        InMemoryAuthorityLog log,
        DataAuthority.CommandPort delegate,
        AuthorityFencingCommandPort.PartitionEpochStore epochStore,
        String ownerNode
    ) {
        this((AuthorityLog) log, delegate, epochStore, ownerNode);
    }

    public AuthorityLogCommandWorker(
        KafkaAuthorityLog log,
        DataAuthority.CommandPort delegate,
        AuthorityFencingCommandPort.PartitionEpochStore epochStore,
        String ownerNode
    ) {
        this((AuthorityLog) log, delegate, epochStore, ownerNode);
    }

    AuthorityLogCommandWorker(
        AuthorityLog log,
        DataAuthority.CommandPort delegate,
        AuthorityFencingCommandPort.PartitionEpochStore epochStore,
        String ownerNode
    ) {
        this.log = Objects.requireNonNull(log, "log");
        this.processor = new AuthorityLogCommandProcessor(log, Objects.requireNonNull(delegate, "delegate"));
        this.epochStore = Objects.requireNonNull(epochStore, "epochStore");
        if (ownerNode == null || ownerNode.isBlank()) {
            throw new IllegalArgumentException("ownerNode is required");
        }
        this.ownerNode = ownerNode;
    }

    public CompletionStage<PartitionResult> processPartition(
        String domain,
        int partition,
        long afterOffset,
        int maxRecords
    ) {
        AuthorityDomainTopology.DomainTopology topology = AuthorityDomainTopology.domain(domain);
        List<AuthorityLogRecord> records = log.records(
            topology.commandTopic(),
            partition,
            afterOffset,
            maxRecords
        );
        return processRecords(topology.domain(), partition, afterOffset, records);
    }

    public CompletionStage<PartitionResult> processRecords(
        String domain,
        int partition,
        List<AuthorityLogRecord> records
    ) {
        Objects.requireNonNull(records, "records");
        long afterOffset = records.stream()
            .mapToLong(AuthorityLogRecord::offset)
            .min()
            .orElse(0L) - 1L;
        AuthorityDomainTopology.DomainTopology topology = AuthorityDomainTopology.domain(domain);
        AuthorityWriterClaim claim = epochStore.claimEpoch(
            topology.domain(),
            topology.commandTopic(),
            partitionFencingScope(topology.domain(), partition),
            ownerNode
        );
        return processRecords(domain, partition, afterOffset, records, claim);
    }

    public CompletionStage<PartitionResult> processRecords(
        String domain,
        int partition,
        List<AuthorityLogRecord> records,
        AuthorityWriterClaim claim
    ) {
        Objects.requireNonNull(records, "records");
        long afterOffset = records.stream()
            .mapToLong(AuthorityLogRecord::offset)
            .min()
            .orElse(0L) - 1L;
        return processRecords(domain, partition, afterOffset, records, claim);
    }

    private CompletionStage<PartitionResult> processRecords(
        String domain,
        int partition,
        long afterOffset,
        List<AuthorityLogRecord> records
    ) {
        AuthorityDomainTopology.DomainTopology topology = AuthorityDomainTopology.domain(domain);
        AuthorityWriterClaim claim = epochStore.claimEpoch(
            topology.domain(),
            topology.commandTopic(),
            partitionFencingScope(topology.domain(), partition),
            ownerNode
        );
        return processRecords(domain, partition, afterOffset, records, claim);
    }

    private CompletionStage<PartitionResult> processRecords(
        String domain,
        int partition,
        long afterOffset,
        List<AuthorityLogRecord> records,
        AuthorityWriterClaim claim
    ) {
        AuthorityDomainTopology.DomainTopology topology = AuthorityDomainTopology.domain(domain);
        if (partition < 0 || partition >= topology.partitionCount()) {
            throw new IllegalArgumentException("Authority partition " + partition
                + " is outside domain " + topology.domain() + " partition count " + topology.partitionCount());
        }
        validateClaim(topology, partition, claim);
        List<AuthorityLogRecord> immutableRecords = records == null ? List.of() : List.copyOf(records);
        PartitionAccumulator accumulator = new PartitionAccumulator(
            topology.domain(),
            topology.commandTopic(),
            partition,
            afterOffset,
            claim,
            immutableRecords.size()
        );
        CompletionStage<PartitionAccumulator> chain = CompletableFuture.completedFuture(accumulator);
        for (AuthorityLogRecord record : immutableRecords) {
            validateRecord(topology, partition, record);
            if (record.kind() != AuthorityLogTopicKind.COMMAND) {
                continue;
            }
            chain = chain.thenCompose(current -> processor.process(record, claim)
                .thenApply(current::add));
        }
        return chain.thenApply(PartitionAccumulator::result);
    }

    private static void validateClaim(
        AuthorityDomainTopology.DomainTopology topology,
        int partition,
        AuthorityWriterClaim claim
    ) {
        Objects.requireNonNull(claim, "claim");
        String partitionKey = partitionFencingScope(topology.domain(), partition);
        if (!topology.domain().equals(claim.commandDomain())) {
            throw new IllegalArgumentException("Authority writer claim domain does not match worker domain");
        }
        if (!topology.commandTopic().equals(claim.commandTopic())) {
            throw new IllegalArgumentException("Authority writer claim topic does not match worker topic");
        }
        if (!partitionKey.equals(claim.partitionKey())) {
            throw new IllegalArgumentException("Authority writer claim partition key does not match worker partition");
        }
    }

    private static void validateRecord(
        AuthorityDomainTopology.DomainTopology topology,
        int partition,
        AuthorityLogRecord record
    ) {
        Objects.requireNonNull(record, "record");
        if (!topology.commandTopic().equals(record.topic())) {
            throw new IllegalArgumentException("Authority worker for " + topology.domain()
                + " cannot process topic " + record.topic());
        }
        if (record.partition() != partition) {
            throw new IllegalArgumentException("Authority worker partition " + partition
                + " cannot process record partition " + record.partition());
        }
    }

    static String partitionFencingScope(String domain, int partition) {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("domain is required");
        }
        if (partition < 0) {
            throw new IllegalArgumentException("partition must be non-negative");
        }
        return domain + ":lane:" + partition;
    }

    private static final class PartitionAccumulator {
        private final String domain;
        private final String commandTopic;
        private final int partition;
        private final long afterOffset;
        private final AuthorityWriterClaim claim;
        private final int scannedCount;
        private final List<AuthorityLogCommandProcessor.ProcessingResult> processed = new ArrayList<>();
        private long lastProcessedOffset = -1L;

        private PartitionAccumulator(
            String domain,
            String commandTopic,
            int partition,
            long afterOffset,
            AuthorityWriterClaim claim,
            int scannedCount
        ) {
            this.domain = domain;
            this.commandTopic = commandTopic;
            this.partition = partition;
            this.afterOffset = afterOffset;
            this.claim = claim;
            this.scannedCount = scannedCount;
        }

        private PartitionAccumulator add(AuthorityLogCommandProcessor.ProcessingResult result) {
            processed.add(result);
            lastProcessedOffset = Math.max(lastProcessedOffset, result.commandRecord().offset());
            return this;
        }

        private PartitionResult result() {
            return new PartitionResult(
                domain,
                commandTopic,
                partition,
                afterOffset,
                scannedCount,
                processed.size(),
                lastProcessedOffset,
                claim,
                processed
            );
        }
    }

    public record PartitionResult(
        String domain,
        String commandTopic,
        int partition,
        long afterOffset,
        int scannedCount,
        int processedCount,
        long lastProcessedOffset,
        AuthorityWriterClaim writerClaim,
        List<AuthorityLogCommandProcessor.ProcessingResult> processed
    ) {
        public PartitionResult {
            domain = requireText(domain, "domain");
            commandTopic = requireText(commandTopic, "commandTopic");
            if (partition < 0) {
                throw new IllegalArgumentException("partition must be non-negative");
            }
            if (afterOffset < -1L) {
                throw new IllegalArgumentException("afterOffset must be -1 or greater");
            }
            if (scannedCount < 0) {
                throw new IllegalArgumentException("scannedCount must not be negative");
            }
            if (processedCount < 0 || processedCount > scannedCount) {
                throw new IllegalArgumentException("processedCount must be within scannedCount");
            }
            writerClaim = Objects.requireNonNull(writerClaim, "writerClaim");
            processed = processed == null ? List.of() : List.copyOf(processed);
            if (processed.size() != processedCount) {
                throw new IllegalArgumentException("processed result count does not match processedCount");
            }
        }

        private static String requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return value;
        }
    }
}
