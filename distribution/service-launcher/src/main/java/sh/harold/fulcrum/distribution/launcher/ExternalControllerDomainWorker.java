package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import sh.harold.fulcrum.data.store.kafka.KafkaClientBundle;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.function.Consumer;
import java.util.function.Function;

final class ExternalControllerDomainWorker implements ControllerWorkerPoller {
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(100);

    private final RuntimeExternalClients.ControllerClients clients;
    private final KafkaClientBundle kafka;
    private final String domain;
    private final String commandTopic;
    private final String eventTopic;
    private final String stateTopic;
    private final String responseTopic;
    private final Function<ConsumerRecord<String, String>, ControllerDomainResult> handler;
    private final Queue<ConsumerRecord<String, String>> pendingRecords = new ArrayDeque<>();
    private boolean subscribed;

    ExternalControllerDomainWorker(
            RuntimeExternalClients.ControllerClients clients,
            String domain,
            Function<ConsumerRecord<String, String>, ControllerDomainResult> handler,
            Consumer<ConsumerRecord<String, String>> stateReplayer) {
        this.clients = Objects.requireNonNull(clients, "clients");
        this.domain = requireNonBlank(domain, "domain");
        this.kafka = clients.controlKafka(domain);
        this.commandTopic = "ctrl.cmd." + domain;
        this.eventTopic = "ctrl.evt." + domain;
        this.stateTopic = stateTopic(clients.settings().controlStateTopic(), domain);
        this.responseTopic = "ctrl.rsp." + domain;
        this.handler = Objects.requireNonNull(handler, "handler");
        KafkaStateTopicReplayer.replay(kafka, stateTopic, POLL_TIMEOUT, Objects.requireNonNull(stateReplayer, "stateReplayer"));
    }

    @Override
    public Optional<ControllerRuntimeReceipt> handleNext() {
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
        ControllerDomainResult result = handler.apply(record);
        publish(result.emissions());
        commit(record);
        return Optional.of(new ControllerRuntimeReceipt(domain, result.commandId()));
    }

    private void subscribeOnce() {
        if (!subscribed) {
            kafka.subscribe(List.of(commandTopic));
            subscribed = true;
        }
    }

    private void publish(List<ControlLogEmission> emissions) {
        for (ControlLogEmission emission : emissions) {
            kafka.producer().send(new ProducerRecord<>(topic(emission.kind()), emission.key(), emission.value()));
        }
        kafka.producer().flush();
    }

    private void commit(ConsumerRecord<String, String> record) {
        kafka.consumer().commitSync(Map.of(
                new TopicPartition(record.topic(), record.partition()),
                new OffsetAndMetadata(record.offset() + 1)));
    }

    private String topic(String emissionKind) {
        return switch (emissionKind) {
            case "EVENT" -> eventTopic;
            case "RESPONSE" -> responseTopic;
            case "HOST_COMMAND" -> clients.settings().hostCommandTopic();
            case "PROXY_COMMAND" -> clients.settings().proxyRouteCommandTopic();
            case "SHARED_SHARD_ALLOCATION_COMMAND" -> "ctrl.cmd." + ControllerWorkerCatalog.SHARED_SHARD_ALLOCATION;
            default -> stateTopic;
        };
    }

    private static String stateTopic(String configuredTopic, String domain) {
        String checked = requireNonBlank(configuredTopic, "configuredTopic");
        if (checked.endsWith("." + domain)) {
            return checked;
        }
        return checked + "." + domain;
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    record ControllerDomainResult(
            String commandId,
            List<ControlLogEmission> emissions) {
        ControllerDomainResult {
            commandId = requireNonBlank(commandId, "commandId");
            emissions = List.copyOf(Objects.requireNonNull(emissions, "emissions"));
        }
    }
}
