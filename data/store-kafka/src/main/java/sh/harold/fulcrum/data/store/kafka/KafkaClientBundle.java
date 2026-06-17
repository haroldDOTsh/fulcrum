package sh.harold.fulcrum.data.store.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

public final class KafkaClientBundle implements AutoCloseable {
    private final String bootstrapServers;
    private final String clientId;
    private final String groupId;
    private final Producer<String, String> producer;
    private final Consumer<String, String> consumer;

    private KafkaClientBundle(
            String bootstrapServers,
            String clientId,
            String groupId,
            Producer<String, String> producer,
            Consumer<String, String> consumer) {
        this.bootstrapServers = requireNonBlank(bootstrapServers, "bootstrapServers");
        this.clientId = requireNonBlank(clientId, "clientId");
        this.groupId = requireNonBlank(groupId, "groupId");
        this.producer = Objects.requireNonNull(producer, "producer");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
    }

    public static KafkaClientBundle create(String bootstrapServers, String clientId, String groupId) {
        String checkedBootstrapServers = requireNonBlank(bootstrapServers, "bootstrapServers");
        String checkedClientId = requireNonBlank(clientId, "clientId");
        String checkedGroupId = requireNonBlank(groupId, "groupId");
        return new KafkaClientBundle(
                checkedBootstrapServers,
                checkedClientId,
                checkedGroupId,
                new KafkaProducer<>(producerProperties(checkedBootstrapServers, checkedClientId)),
                new KafkaConsumer<>(consumerProperties(checkedBootstrapServers, checkedClientId, checkedGroupId)));
    }

    public String description() {
        return "bootstrapServers=" + bootstrapServers + "|clientId=" + clientId + "|groupId=" + groupId;
    }

    public Producer<String, String> producer() {
        return producer;
    }

    public Consumer<String, String> consumer() {
        return consumer;
    }

    public void subscribe(Collection<String> topics) {
        consumer.subscribe(List.copyOf(Objects.requireNonNull(topics, "topics")));
    }

    @Override
    public void close() {
        consumer.close(Duration.ofSeconds(5));
        producer.close(Duration.ofSeconds(5));
    }

    private static Properties producerProperties(String bootstrapServers, String clientId) {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.CLIENT_ID_CONFIG, clientId + "-producer");
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        return properties;
    }

    private static Properties consumerProperties(String bootstrapServers, String clientId, String groupId) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId + "-consumer");
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return properties;
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
