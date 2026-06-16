package sh.harold.fulcrum.data.store.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import sh.harold.fulcrum.data.authority.runtime.AuthorityOffset;
import sh.harold.fulcrum.data.authority.runtime.AuthorityOffsetCommitter;

import java.util.Map;
import java.util.Objects;

public final class KafkaAuthorityOffsetCommitter implements AuthorityOffsetCommitter {
    private final Consumer<?, ?> consumer;

    public KafkaAuthorityOffsetCommitter(Consumer<?, ?> consumer) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
    }

    @Override
    public void commit(AuthorityOffset offset) {
        TopicPartition partition = new TopicPartition(offset.source(), offset.partition());
        consumer.commitSync(Map.of(partition, new OffsetAndMetadata(offset.position() + 1)));
    }
}
