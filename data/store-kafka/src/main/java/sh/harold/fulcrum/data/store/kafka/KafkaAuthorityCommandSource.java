package sh.harold.fulcrum.data.store.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.Consumer;
import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.data.authority.runtime.AuthorityCommandDelivery;
import sh.harold.fulcrum.data.authority.runtime.AuthorityCommandSource;
import sh.harold.fulcrum.data.authority.runtime.AuthorityOffset;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class KafkaAuthorityCommandSource<C extends CommandPayload> implements AuthorityCommandSource<C> {
    private final Consumer<String, String> consumer;
    private final Duration pollTimeout;
    private final KafkaAuthorityCommandDecoder<C> decoder;

    public KafkaAuthorityCommandSource(
            Consumer<String, String> consumer,
            Duration pollTimeout,
            KafkaAuthorityCommandDecoder<C> decoder) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.pollTimeout = Objects.requireNonNull(pollTimeout, "pollTimeout");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        if (pollTimeout.isNegative() || pollTimeout.isZero()) {
            throw new IllegalArgumentException("pollTimeout must be positive");
        }
    }

    @Override
    public Optional<AuthorityCommandDelivery<C>> poll() {
        ConsumerRecords<String, String> records = consumer.poll(pollTimeout);
        if (records.isEmpty()) {
            return Optional.empty();
        }
        ConsumerRecord<String, String> record = records.iterator().next();
        return Optional.of(new AuthorityCommandDelivery<>(
                decoder.decode(record),
                new AuthorityOffset(record.topic(), record.partition(), record.offset())));
    }
}
