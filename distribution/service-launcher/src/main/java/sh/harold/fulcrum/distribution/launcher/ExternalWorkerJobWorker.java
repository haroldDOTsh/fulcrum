package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import sh.harold.fulcrum.data.store.kafka.KafkaClientBundle;
import sh.harold.fulcrum.host.worker.WorkerAgentRuntime;
import sh.harold.fulcrum.host.worker.WorkerJobHandler;
import sh.harold.fulcrum.host.worker.WorkerJobReceipt;
import sh.harold.fulcrum.host.worker.WorkerJobRequest;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;

final class ExternalWorkerJobWorker implements WorkerJobPoller {
    static final String DOMAIN = "worker-jobs";
    static final String CLIENT_ID = "fulcrum-worker-agent";
    static final String GROUP_ID = "fulcrum-worker-agent";

    private static final Duration POLL_TIMEOUT = Duration.ofMillis(100);

    private final RuntimeExternalClients.WorkerClients clients;
    private final KafkaClientBundle kafka;
    private final WorkerAgentRuntime runtime;
    private final WorkerJobHandler handler;
    private final Clock clock;
    private final Queue<ConsumerRecord<String, String>> pendingRecords = new ArrayDeque<>();
    private boolean subscribed;

    ExternalWorkerJobWorker(
            RuntimeExternalClients.WorkerClients clients,
            WorkerAgentRuntime runtime,
            WorkerJobHandler handler,
            Clock clock) {
        this.clients = Objects.requireNonNull(clients, "clients");
        this.kafka = clients.workerKafka();
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<WorkerJobReceipt> handleNext() {
        subscribeOnce();
        if (pendingRecords.isEmpty()) {
            ConsumerRecords<String, String> records = kafka.consumer().poll(POLL_TIMEOUT);
            for (ConsumerRecord<String, String> record : records) {
                pendingRecords.add(record);
            }
        }
        ConsumerRecord<String, String> record = pendingRecords.poll();
        if (record == null) {
            return Optional.empty();
        }
        WorkerJobRequest request = WorkerJobWireCodec.decodeRequest(record);
        WorkerJobReceipt receipt = runtime.handle(request, handler, clock.instant());
        publish(receipt);
        commit(record);
        return Optional.of(receipt);
    }

    private void subscribeOnce() {
        if (!subscribed) {
            kafka.subscribe(List.of(clients.settings().jobTopic()));
            subscribed = true;
        }
    }

    private void publish(WorkerJobReceipt receipt) {
        kafka.producer().send(new ProducerRecord<>(
                clients.settings().resultTopic(),
                receipt.jobId().value(),
                WorkerJobWireCodec.encodeReceipt(receipt)));
        kafka.producer().flush();
    }

    private void commit(ConsumerRecord<String, String> record) {
        kafka.consumer().commitSync(Map.of(
                new TopicPartition(record.topic(), record.partition()),
                new OffsetAndMetadata(record.offset() + 1)));
    }
}
