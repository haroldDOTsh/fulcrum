package sh.harold.fulcrum.api.data.impl.authority;

import com.google.gson.Gson;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.TopicPartitionInfo;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Kafka-backed authority log for cmd/evt/state/rsp records.
 */
public final class KafkaAuthorityLog implements AuthorityLog, AutoCloseable {
    private static final String CLEANUP_POLICY = "cleanup.policy";

    private final Admin admin;
    private final Producer<String, String> producer;
    private final Map<String, AuthorityLogTopicPolicy> policies;
    private final Properties consumerProperties;
    private final Duration operationTimeout;
    private final Gson gson = new Gson();

    public KafkaAuthorityLog(Properties properties) {
        this(
            Admin.create(Objects.requireNonNull(properties, "properties")),
            new KafkaProducer<>(producerProperties(properties)),
            AuthorityLogTopology.policiesByTopic(),
            Duration.ofSeconds(10),
            consumerProperties(properties)
        );
    }

    KafkaAuthorityLog(
        Admin admin,
        Producer<String, String> producer,
        Map<String, AuthorityLogTopicPolicy> policies,
        Duration operationTimeout
    ) {
        this(admin, producer, policies, operationTimeout, new Properties());
    }

    KafkaAuthorityLog(
        Admin admin,
        Producer<String, String> producer,
        Map<String, AuthorityLogTopicPolicy> policies,
        Duration operationTimeout,
        Properties consumerProperties
    ) {
        this.admin = Objects.requireNonNull(admin, "admin");
        this.producer = Objects.requireNonNull(producer, "producer");
        this.policies = Map.copyOf(Objects.requireNonNull(policies, "policies"));
        this.operationTimeout = operationTimeout == null ? Duration.ofSeconds(10) : operationTimeout;
        this.consumerProperties = new Properties();
        if (consumerProperties != null) {
            this.consumerProperties.putAll(consumerProperties);
        }
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
        String key = AuthorityLogTopology.key(route);
        int partition = AuthorityLogTopology.partition(route);
        if (partition >= policy.partitionCount()) {
            throw new IllegalStateException("Computed authority log partition exceeds topic partition count");
        }

        Map<String, String> headers = headers(route, kind, policy, partition);
        ProducerRecord<String, String> record = new ProducerRecord<>(
            topic,
            partition,
            key,
            gson.toJson(payload == null ? Map.of() : payload)
        );
        headers.forEach((header, value) -> record.headers().add(new RecordHeader(
            header,
            value.getBytes(StandardCharsets.UTF_8)
        )));

        RecordMetadata metadata = await(producer.send(record), "append authority log record to " + topic);
        return new AuthorityLogRecord(
            metadata.topic(),
            key,
            metadata.partition(),
            metadata.offset(),
            kind,
            payload,
            headers,
            metadata.timestamp()
        );
    }

    public void validateTopology() {
        Map<String, TopicDescription> descriptions = await(
            admin.describeTopics(policies.keySet()).allTopicNames(),
            "describe authority log topics"
        );
        Map<ConfigResource, Config> configs = await(
            admin.describeConfigs(topicResources(policies.keySet())).all(),
            "describe authority log topic configs"
        );
        List<String> violations = topologyViolations(policies, descriptions, cleanupPolicies(configs));
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Authority Kafka topology drift: " + String.join("; ", violations));
        }
    }

    @Override
    public void validateSchema() {
        validateTopology();
    }

    public void createMissingTopics() {
        Set<String> existing = await(admin.listTopics().names(), "list Kafka topics");
        List<NewTopic> missing = policies.values().stream()
            .filter(policy -> !existing.contains(policy.topic()))
            .map(policy -> new NewTopic(policy.topic(), policy.partitionCount(), (short) -1)
                .configs(topicConfig(policy)))
            .toList();
        if (!missing.isEmpty()) {
            await(admin.createTopics(missing).all(), "create authority log topics");
        }
    }

    @Override
    public Map<String, AuthorityLogTopicPolicy> policiesByTopic() {
        return policies;
    }

    @Override
    public List<AuthorityLogRecord> records(String topic, int partition, long afterOffset, int maxRecords) {
        AuthorityLogTopicPolicy policy = policy(topic);
        if (partition < 0 || partition >= policy.partitionCount()) {
            throw new IllegalArgumentException("Kafka authority partition " + partition
                + " is outside topic " + topic + " partition count " + policy.partitionCount());
        }
        if (afterOffset < -1L) {
            throw new IllegalArgumentException("afterOffset must be -1 or greater");
        }
        if (maxRecords <= 0) {
            return List.of();
        }

        Properties properties = new Properties();
        properties.putAll(consumerProperties);
        properties.putIfAbsent("group.id", "authority-log-replay-" + UUID.randomUUID());
        properties.putIfAbsent("enable.auto.commit", "false");
        properties.putIfAbsent("auto.offset.reset", "earliest");
        TopicPartition topicPartition = new TopicPartition(topic, partition);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.assign(List.of(topicPartition));
            if (afterOffset < 0L) {
                consumer.seekToBeginning(List.of(topicPartition));
            } else {
                consumer.seek(topicPartition, afterOffset + 1L);
            }

            List<AuthorityLogRecord> values = new ArrayList<>();
            long deadline = System.nanoTime() + operationTimeout.toNanos();
            while (values.size() < maxRecords && System.nanoTime() < deadline) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(100));
                for (ConsumerRecord<String, String> record : polled.records(topicPartition)) {
                    values.add(authorityRecord(policy, record));
                    if (values.size() == maxRecords) {
                        break;
                    }
                }
                if (polled.isEmpty() && atEnd(consumer, topicPartition)) {
                    break;
                }
            }
            return List.copyOf(values);
        }
    }

    @Override
    public void close() {
        producer.close(operationTimeout);
        admin.close(operationTimeout);
    }

    KafkaConsumer<String, String> commandConsumer(String consumerGroup) {
        if (consumerGroup == null || consumerGroup.isBlank()) {
            throw new IllegalArgumentException("consumerGroup is required");
        }
        Properties properties = new Properties();
        properties.putAll(consumerProperties);
        properties.put("group.id", consumerGroup);
        properties.put("enable.auto.commit", "false");
        properties.putIfAbsent("auto.offset.reset", "earliest");
        return new KafkaConsumer<>(properties);
    }

    private static Properties producerProperties(Properties source) {
        Properties properties = new Properties();
        properties.putAll(source);
        properties.putIfAbsent("key.serializer", StringSerializer.class.getName());
        properties.putIfAbsent("value.serializer", StringSerializer.class.getName());
        properties.putIfAbsent("acks", "all");
        properties.putIfAbsent("enable.idempotence", "true");
        properties.putIfAbsent("max.in.flight.requests.per.connection", "5");
        return properties;
    }

    private static Properties consumerProperties(Properties source) {
        Properties properties = new Properties();
        properties.putAll(source);
        properties.putIfAbsent("key.deserializer", StringDeserializer.class.getName());
        properties.putIfAbsent("value.deserializer", StringDeserializer.class.getName());
        return properties;
    }

    private Map<String, String> headers(
        AuthorityCommandRoute route,
        AuthorityLogTopicKind kind,
        AuthorityLogTopicPolicy policy,
        int partition
    ) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("authority-log-kind", kind.name());
        headers.put("authority-domain", route.domain());
        headers.put("authority-partition-key", route.partitionKey());
        headers.put("authority-partition", Integer.toString(partition));
        headers.put("authority-retention-class", policy.retentionClass());
        headers.put("authority-key-rule", policy.keyRule());
        headers.put("authority-route-manifest-fingerprint", DataAuthorityCommandContracts.routeManifestFingerprint());
        return Map.copyOf(headers);
    }

    AuthorityLogRecord authorityRecord(ConsumerRecord<String, String> record) {
        Objects.requireNonNull(record, "record");
        return authorityRecord(policy(record.topic()), record);
    }

    private AuthorityLogRecord authorityRecord(
        AuthorityLogTopicPolicy policy,
        ConsumerRecord<String, String> record
    ) {
        return new AuthorityLogRecord(
            record.topic(),
            record.key(),
            record.partition(),
            record.offset(),
            policy.kind(),
            payload(record.value()),
            headers(record),
            record.timestamp()
        );
    }

    private Map<String, Object> payload(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<?, ?> parsed = gson.fromJson(json, Map.class);
        if (parsed == null || parsed.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        parsed.forEach((key, value) -> {
            if (key != null) {
                values.put(key.toString(), value);
            }
        });
        return Map.copyOf(values);
    }

    private static Map<String, String> headers(ConsumerRecord<String, String> record) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Header header : record.headers()) {
            values.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
        }
        return Map.copyOf(values);
    }

    private boolean atEnd(KafkaConsumer<String, String> consumer, TopicPartition topicPartition) {
        Long endOffset = consumer.endOffsets(List.of(topicPartition)).get(topicPartition);
        return endOffset != null && consumer.position(topicPartition) >= endOffset;
    }

    private AuthorityLogTopicPolicy policy(String topic) {
        AuthorityLogTopicPolicy policy = policies.get(topic);
        if (policy == null) {
            throw new IllegalArgumentException("Unknown authority log topic " + topic);
        }
        return policy;
    }

    private Map<String, String> topicConfig(AuthorityLogTopicPolicy policy) {
        Map<String, String> config = new LinkedHashMap<>();
        if (policy.compacted()) {
            config.put(CLEANUP_POLICY, "compact");
        }
        return config;
    }

    private static List<ConfigResource> topicResources(Set<String> topics) {
        return topics.stream()
            .sorted()
            .map(KafkaAuthorityLog::topicResource)
            .toList();
    }

    private static ConfigResource topicResource(String topic) {
        return new ConfigResource(ConfigResource.Type.TOPIC, topic);
    }

    private static String configValue(Config config, String name) {
        if (config == null) {
            return null;
        }
        ConfigEntry entry = config.get(name);
        return entry == null ? null : entry.value();
    }

    static List<String> topologyViolations(
        Map<String, AuthorityLogTopicPolicy> policies,
        Map<String, TopicDescription> descriptions,
        Map<String, String> cleanupPolicies
    ) {
        List<String> violations = new ArrayList<>();
        policies.forEach((topic, policy) -> {
            TopicDescription description = descriptions.get(topic);
            if (description == null) {
                violations.add(topic + " missing");
                return;
            }
            if (description.partitions().size() != policy.partitionCount()) {
                violations.add(topic + " partitions=" + description.partitions().size()
                    + " expected=" + policy.partitionCount());
            }
            boolean compacted = hasCleanupPolicy(cleanupPolicies.get(topic), "compact");
            if (policy.compacted() != compacted) {
                violations.add(topic + " compacted=" + compacted + " expected=" + policy.compacted());
            }
        });
        return List.copyOf(violations);
    }

    private static Map<String, String> cleanupPolicies(Map<ConfigResource, Config> configs) {
        Map<String, String> values = new LinkedHashMap<>();
        configs.forEach((resource, config) -> values.put(resource.name(), configValue(config, CLEANUP_POLICY)));
        return Map.copyOf(values);
    }

    private static boolean hasCleanupPolicy(String cleanupPolicy, String expected) {
        if (cleanupPolicy == null || cleanupPolicy.isBlank()) {
            return false;
        }
        for (String policy : cleanupPolicy.split(",")) {
            if (expected.equals(policy.trim())) {
                return true;
            }
        }
        return false;
    }

    private <T> T await(java.util.concurrent.Future<T> future, String action) {
        try {
            return future.get(operationTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while trying to " + action, exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Failed to " + action, exception.getCause());
        } catch (TimeoutException exception) {
            throw new IllegalStateException("Timed out trying to " + action, exception);
        }
    }

    public record TopologySummary(
        int topicCount,
        int partitionCount,
        int compactedTopicCount,
        Set<String> topics
    ) {
        public TopologySummary {
            topics = topics == null ? Set.of() : Set.copyOf(new TreeSet<>(topics));
        }
    }

    public TopologySummary summary() {
        int partitions = policies.values().stream()
            .mapToInt(AuthorityLogTopicPolicy::partitionCount)
            .sum();
        int compacted = (int) policies.values().stream()
            .filter(AuthorityLogTopicPolicy::compacted)
            .count();
        return new TopologySummary(policies.size(), partitions, compacted, policies.keySet());
    }
}
