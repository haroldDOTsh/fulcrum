package sh.harold.fulcrum.api.data.impl.authority;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory Kafka-shaped authority log used for topology and replay contract tests.
 */
public final class InMemoryAuthorityLog implements AuthorityLog {
    private final Map<String, AuthorityLogTopicPolicy> policies;
    private final ConcurrentMap<TopicPartition, List<AuthorityLogRecord>> records = new ConcurrentHashMap<>();

    public InMemoryAuthorityLog() {
        this(AuthorityLogTopology.policiesByTopic());
    }

    public InMemoryAuthorityLog(Map<String, AuthorityLogTopicPolicy> policies) {
        this.policies = Map.copyOf(Objects.requireNonNull(policies, "policies"));
    }

    @Override
    public AuthorityLogRecord append(
        AuthorityCommandRoute route,
        AuthorityLogTopicKind kind,
        Map<String, Object> payload
    ) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(kind, "kind");
        String topic = kind.topic(route);
        AuthorityLogTopicPolicy policy = policy(topic);
        int partition = AuthorityLogTopology.partition(route);
        if (partition >= policy.partitionCount()) {
            throw new IllegalStateException("Computed authority log partition exceeds topic partition count");
        }
        TopicPartition topicPartition = new TopicPartition(topic, partition);
        List<AuthorityLogRecord> partitionRecords = records.computeIfAbsent(
            topicPartition,
            ignored -> new ArrayList<>()
        );
        synchronized (partitionRecords) {
            AuthorityLogRecord record = new AuthorityLogRecord(
                topic,
                AuthorityLogTopology.key(route),
                partition,
                partitionRecords.size(),
                kind,
                payload,
                System.currentTimeMillis()
            );
            partitionRecords.add(record);
            return record;
        }
    }

    public List<AuthorityLogRecord> records(String topic, int partition) {
        List<AuthorityLogRecord> partitionRecords = records.get(new TopicPartition(topic, partition));
        if (partitionRecords == null) {
            return List.of();
        }
        synchronized (partitionRecords) {
            return List.copyOf(partitionRecords);
        }
    }

    @Override
    public List<AuthorityLogRecord> records(String topic, int partition, long afterOffset, int maxRecords) {
        if (afterOffset < -1L) {
            throw new IllegalArgumentException("afterOffset must be -1 or greater");
        }
        if (maxRecords <= 0) {
            return List.of();
        }
        return records(topic, partition).stream()
            .filter(record -> record.offset() > afterOffset)
            .limit(maxRecords)
            .toList();
    }

    public Map<String, AuthorityLogRecord> compacted(String topic) {
        AuthorityLogTopicPolicy policy = policy(topic);
        if (!policy.compacted()) {
            throw new IllegalArgumentException("Topic " + topic + " is not compacted");
        }
        Map<String, AuthorityLogRecord> latest = new LinkedHashMap<>();
        records.entrySet().stream()
            .filter(entry -> entry.getKey().topic().equals(topic))
            .sorted(Comparator.comparingInt(entry -> entry.getKey().partition()))
            .forEach(entry -> {
                List<AuthorityLogRecord> partitionRecords = entry.getValue();
                synchronized (partitionRecords) {
                    partitionRecords.forEach(record -> latest.put(record.key(), record));
                }
            });
        return Map.copyOf(latest);
    }

    @Override
    public Map<String, AuthorityLogTopicPolicy> policiesByTopic() {
        return policies;
    }

    private AuthorityLogTopicPolicy policy(String topic) {
        AuthorityLogTopicPolicy policy = policies.get(topic);
        if (policy == null) {
            throw new IllegalArgumentException("Unknown authority log topic " + topic);
        }
        return policy;
    }

    private record TopicPartition(String topic, int partition) {
    }
}
