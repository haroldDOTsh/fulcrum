package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import sh.harold.fulcrum.data.store.kafka.KafkaClientBundle;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

final class KafkaStateTopicReplayer {
    private KafkaStateTopicReplayer() {
    }

    static int replay(
            KafkaClientBundle kafka,
            String topic,
            Duration pollTimeout,
            Consumer<ConsumerRecord<String, String>> stateHandler) {
        Objects.requireNonNull(kafka, "kafka");
        String checkedTopic = requireNonBlank(topic, "topic");
        Objects.requireNonNull(pollTimeout, "pollTimeout");
        Objects.requireNonNull(stateHandler, "stateHandler");

        List<PartitionInfo> partitionInfos;
        try {
            partitionInfos = kafka.consumer().partitionsFor(checkedTopic, pollTimeout);
        } catch (TimeoutException exception) {
            return 0;
        }

        List<TopicPartition> partitions = partitionInfos.stream()
                .map(PartitionInfo::partition)
                .map(partition -> new TopicPartition(checkedTopic, partition))
                .toList();
        if (partitions.isEmpty()) {
            return 0;
        }

        kafka.consumer().assign(partitions);
        kafka.consumer().seekToBeginning(partitions);
        Map<TopicPartition, Long> endOffsets = kafka.consumer().endOffsets(partitions);
        int replayed = 0;
        while (!consumedToEnd(kafka, endOffsets)) {
            for (ConsumerRecord<String, String> record : kafka.consumer().poll(pollTimeout)) {
                stateHandler.accept(record);
                replayed++;
            }
        }
        kafka.consumer().unsubscribe();
        return replayed;
    }

    private static boolean consumedToEnd(KafkaClientBundle kafka, Map<TopicPartition, Long> endOffsets) {
        for (Map.Entry<TopicPartition, Long> entry : endOffsets.entrySet()) {
            if (kafka.consumer().position(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
