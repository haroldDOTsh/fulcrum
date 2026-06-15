package sh.harold.fulcrum.api.data.impl.authority;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded poll loop for rebuilding one projection from state-topic partitions.
 */
public final class AuthorityStateProjectionWorkerLoop {
    private final AuthorityStateProjectionWorker worker;
    private final String domain;
    private final List<Integer> partitions;
    private final int maxRecordsPerPartition;
    private final AuthorityStateProjectionCursorStore cursorStore;

    public AuthorityStateProjectionWorkerLoop(
        AuthorityStateProjectionWorker worker,
        String domain,
        Collection<Integer> partitions,
        int maxRecordsPerPartition
    ) {
        this(
            worker,
            domain,
            partitions,
            maxRecordsPerPartition,
            AuthorityStateProjectionCursorStore.inMemory()
        );
    }

    public AuthorityStateProjectionWorkerLoop(
        AuthorityStateProjectionWorker worker,
        String domain,
        Collection<Integer> partitions,
        int maxRecordsPerPartition,
        AuthorityStateProjectionCursorStore cursorStore
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

    public PollResult pollOnce() {
        Map<Integer, AuthorityStateProjectionWorker.PartitionResult> results = new LinkedHashMap<>();
        for (int partition : partitions) {
            AuthorityStateProjectionWorker.PartitionResult result = pollPartition(partition);
            results.put(partition, result);
        }
        return new PollResult(worker.projectionName(), domain, results);
    }

    public long committedOffset(int partition) {
        return cursorStore.committedOffset(worker.projectionName(), worker.projectionVersion(), domain, partition);
    }

    public String projectionName() {
        return worker.projectionName();
    }

    public String domain() {
        return domain;
    }

    public Map<Integer, Long> committedOffsets() {
        Map<Integer, Long> values = new LinkedHashMap<>();
        for (int partition : partitions) {
            values.put(partition, committedOffset(partition));
        }
        return Map.copyOf(values);
    }

    private AuthorityStateProjectionWorker.PartitionResult pollPartition(int partition) {
        long afterOffset = committedOffset(partition);
        AuthorityStateProjectionWorker.PartitionResult result =
            worker.processPartition(domain, partition, afterOffset, maxRecordsPerPartition);
        if (result.lastProcessedOffset() > afterOffset) {
            cursorStore.recordApplied(result);
        }
        return result;
    }

    private static int requirePartition(AuthorityDomainTopology.DomainTopology topology, Integer partition) {
        if (partition == null) {
            throw new IllegalArgumentException("partition must not be null");
        }
        if (partition < 0 || partition >= topology.partitionCount()) {
            throw new IllegalArgumentException("Authority state projection partition " + partition
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

    public record PollResult(
        String projectionName,
        String domain,
        Map<Integer, AuthorityStateProjectionWorker.PartitionResult> partitions
    ) {
        public PollResult {
            projectionName = requireText(projectionName, "projectionName");
            domain = requireText(domain, "domain");
            partitions = partitions == null ? Map.of() : Map.copyOf(partitions);
        }

        public int scannedCount() {
            return partitions.values().stream()
                .mapToInt(AuthorityStateProjectionWorker.PartitionResult::scannedCount)
                .sum();
        }

        public int processedCount() {
            return partitions.values().stream()
                .mapToInt(AuthorityStateProjectionWorker.PartitionResult::processedCount)
                .sum();
        }

        public int restoredCount() {
            return partitions.values().stream()
                .mapToInt(AuthorityStateProjectionWorker.PartitionResult::restoredCount)
                .sum();
        }

        public int idempotentSkipCount() {
            return partitions.values().stream()
                .mapToInt(AuthorityStateProjectionWorker.PartitionResult::idempotentSkipCount)
                .sum();
        }
    }
}
