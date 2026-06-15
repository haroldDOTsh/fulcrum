package sh.harold.fulcrum.api.data.impl.authority;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Bounded poll loop for authority command partitions owned by one worker instance.
 */
public final class AuthorityLogCommandWorkerLoop {
    private final AuthorityLogCommandWorker worker;
    private final String domain;
    private final List<Integer> partitions;
    private final int maxRecordsPerPartition;
    private final AuthorityCommandConsumerCursorStore cursorStore;

    public AuthorityLogCommandWorkerLoop(
        AuthorityLogCommandWorker worker,
        String domain,
        Collection<Integer> partitions,
        int maxRecordsPerPartition
    ) {
        this(
            worker,
            domain,
            partitions,
            maxRecordsPerPartition,
            AuthorityCommandConsumerCursorStore.inMemory()
        );
    }

    public AuthorityLogCommandWorkerLoop(
        AuthorityLogCommandWorker worker,
        String domain,
        Collection<Integer> partitions,
        int maxRecordsPerPartition,
        AuthorityCommandConsumerCursorStore cursorStore
    ) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.domain = requireText(domain, "domain");
        if (partitions == null || partitions.isEmpty()) {
            throw new IllegalArgumentException("at least one partition is required");
        }
        AuthorityDomainTopology.DomainTopology topology = AuthorityDomainTopology.domain(domain);
        this.partitions = partitions.stream()
            .map(partition -> requirePartition(topology, partition))
            .distinct()
            .sorted()
            .toList();
        if (maxRecordsPerPartition <= 0) {
            throw new IllegalArgumentException("maxRecordsPerPartition must be positive");
        }
        this.maxRecordsPerPartition = maxRecordsPerPartition;
        this.cursorStore = Objects.requireNonNull(cursorStore, "cursorStore");
    }

    public CompletionStage<PollResult> pollOnce() {
        PollAccumulator accumulator = new PollAccumulator(domain);
        CompletionStage<PollAccumulator> chain = CompletableFuture.completedFuture(accumulator);
        for (int partition : partitions) {
            chain = chain.thenCompose(current -> pollPartition(partition)
                .thenApply(current::add));
        }
        return chain.thenApply(PollAccumulator::result);
    }

    public long committedOffset(int partition) {
        return cursorStore.committedOffset(domain, partition);
    }

    public String domain() {
        return domain;
    }

    public Map<Integer, Long> committedOffsets() {
        return cursorStore.committedOffsets(domain, partitions);
    }

    private CompletionStage<AuthorityLogCommandWorker.PartitionResult> pollPartition(int partition) {
        long afterOffset = committedOffset(partition);
        return worker.processPartition(domain, partition, afterOffset, maxRecordsPerPartition)
            .thenApply(result -> {
                if (result.lastProcessedOffset() > afterOffset) {
                    cursorStore.recordApplied(result);
                }
                return result;
            });
    }

    private static int requirePartition(AuthorityDomainTopology.DomainTopology topology, Integer partition) {
        if (partition == null) {
            throw new IllegalArgumentException("partition must not be null");
        }
        if (partition < 0 || partition >= topology.partitionCount()) {
            throw new IllegalArgumentException("Authority partition " + partition
                + " is outside domain " + topology.domain() + " partition count " + topology.partitionCount());
        }
        return partition;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static final class PollAccumulator {
        private final String domain;
        private final Map<Integer, AuthorityLogCommandWorker.PartitionResult> partitions = new LinkedHashMap<>();

        private PollAccumulator(String domain) {
            this.domain = domain;
        }

        private PollAccumulator add(AuthorityLogCommandWorker.PartitionResult result) {
            partitions.put(result.partition(), result);
            return this;
        }

        private PollResult result() {
            return new PollResult(domain, partitions);
        }
    }

    public record PollResult(
        String domain,
        Map<Integer, AuthorityLogCommandWorker.PartitionResult> partitions
    ) {
        public PollResult {
            domain = requireText(domain, "domain");
            partitions = partitions == null ? Map.of() : Map.copyOf(partitions);
        }

        public int scannedCount() {
            return partitions.values().stream()
                .mapToInt(AuthorityLogCommandWorker.PartitionResult::scannedCount)
                .sum();
        }

        public int processedCount() {
            return partitions.values().stream()
                .mapToInt(AuthorityLogCommandWorker.PartitionResult::processedCount)
                .sum();
        }
    }
}
