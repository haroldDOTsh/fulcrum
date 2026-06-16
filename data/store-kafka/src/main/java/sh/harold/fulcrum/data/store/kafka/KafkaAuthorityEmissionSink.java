package sh.harold.fulcrum.data.store.kafka;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import sh.harold.fulcrum.data.authority.AuthorityEmission;
import sh.harold.fulcrum.data.authority.runtime.AuthorityEmissionSink;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class KafkaAuthorityEmissionSink implements AuthorityEmissionSink {
    private final Producer<String, String> producer;
    private final KafkaAuthorityEmissionTopics topics;
    private final Duration sendTimeout;

    public KafkaAuthorityEmissionSink(
            Producer<String, String> producer,
            KafkaAuthorityEmissionTopics topics,
            Duration sendTimeout) {
        this.producer = Objects.requireNonNull(producer, "producer");
        this.topics = Objects.requireNonNull(topics, "topics");
        this.sendTimeout = Objects.requireNonNull(sendTimeout, "sendTimeout");
        if (sendTimeout.isNegative() || sendTimeout.isZero()) {
            throw new IllegalArgumentException("sendTimeout must be positive");
        }
    }

    @Override
    public void publish(AuthorityEmission emission) {
        topics.topicFor(emission.kind()).ifPresent(topic -> send(topic, emission));
    }

    private void send(String topic, AuthorityEmission emission) {
        try {
            producer.send(new ProducerRecord<>(topic, emission.key(), emission.payload()))
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not publish authority emission to Kafka topic " + topic, exception);
        }
    }
}
