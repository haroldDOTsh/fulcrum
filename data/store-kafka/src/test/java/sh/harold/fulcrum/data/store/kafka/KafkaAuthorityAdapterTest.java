package sh.harold.fulcrum.data.store.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityEmission;
import sh.harold.fulcrum.data.authority.AuthorityEmissionKind;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KafkaAuthorityAdapterTest {
    @Test
    void commandSourceMapsKafkaRecordToAuthorityDeliveryOffset() {
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        TopicPartition partition = new TopicPartition("cmd.test", 0);
        consumer.assign(List.of(partition));
        consumer.updateBeginningOffsets(Map.of(partition, 0L));
        consumer.addRecord(new ConsumerRecord<>("cmd.test", 0, 4L, "aggregate-1", "payload"));

        KafkaAuthorityCommandSource<TestPayload> source = new KafkaAuthorityCommandSource<>(
                consumer,
                Duration.ofMillis(1),
                record -> command(record.key()));

        var delivery = source.poll().orElseThrow();

        assertEquals("cmd.test", delivery.offset().source());
        assertEquals(0, delivery.offset().partition());
        assertEquals(4L, delivery.offset().position());
        assertEquals(new AggregateId("aggregate-1"), delivery.command().envelope().aggregateId());
    }

    @Test
    void emissionSinkPublishesLogEmissionsAndLeavesCacheWritesForValkey() {
        MockProducer<String, String> producer = new MockProducer<>(true, null, new StringSerializer(), new StringSerializer());
        KafkaAuthorityEmissionSink sink = new KafkaAuthorityEmissionSink(
                producer,
                new KafkaAuthorityEmissionTopics("evt.test", "state.test", "rsp.test"),
                Duration.ofSeconds(1));

        sink.publish(new AuthorityEmission(AuthorityEmissionKind.EVENT, "aggregate-1", "event-payload"));
        sink.publish(new AuthorityEmission(AuthorityEmissionKind.CACHE_WRITE, "cache-key", "cache-payload"));

        List<ProducerRecord<String, String>> history = producer.history();
        assertEquals(1, history.size());
        assertEquals("evt.test", history.getFirst().topic());
        assertEquals("aggregate-1", history.getFirst().key());
        assertEquals("event-payload", history.getFirst().value());
    }

    @Test
    void offsetCommitterCommitsNextKafkaPosition() {
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        TopicPartition partition = new TopicPartition("cmd.test", 2);
        consumer.assign(List.of(partition));

        new KafkaAuthorityOffsetCommitter(consumer).commit(new sh.harold.fulcrum.data.authority.runtime.AuthorityOffset("cmd.test", 2, 10L));

        assertEquals(11L, consumer.committed(Set.of(partition)).get(partition).offset());
    }

    private static AuthorityCommand<TestPayload> command(String aggregateId) {
        Instant now = Instant.parse("2026-06-16T00:00:00Z");
        TraceEnvelope trace = new TraceEnvelope(
                "trace-kafka",
                "span-kafka",
                Optional.empty(),
                now,
                "store-kafka-test",
                new InstanceId("instance-store-kafka-test"));
        return new AuthorityCommand<>(
                new CommandEnvelope<>(
                        new CommandId("command-kafka"),
                        new IdempotencyKey("idempotency-kafka"),
                        new PrincipalId("principal-kafka"),
                        new AggregateId(aggregateId),
                        new ContractName("contract-kafka"),
                        new CommandName("command-kafka"),
                        trace,
                        Optional.empty(),
                        new TestPayload()),
                new PrincipalId("principal-kafka"),
                1,
                Optional.empty(),
                "payload-fingerprint-kafka",
                now);
    }

    private record TestPayload() implements CommandPayload {
    }
}
