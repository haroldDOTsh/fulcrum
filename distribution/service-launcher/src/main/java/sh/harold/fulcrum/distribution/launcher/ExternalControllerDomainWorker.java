package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import sh.harold.fulcrum.data.store.kafka.KafkaClientBundle;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
    private final DurableIdempotencyLedger idempotencyLedger;
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
        this.idempotencyLedger = DurableIdempotencyLedger.replay(
                kafka,
                stateTopic,
                POLL_TIMEOUT,
                DurableIdempotencyLedger.CONTROL_RECORD_TYPE);
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
        Optional<ControlCommandLedgerKey> ledgerKey = ControlCommandWireCodec.commandLedgerKey(record);
        ControllerDomainResult result = ledgerKey.flatMap(this::durableReplay)
                .orElseGet(() -> withDurableLedgerEntry(ledgerKey, handler.apply(record)));
        publish(result.emissions());
        commit(record);
        return Optional.of(new ControllerRuntimeReceipt(domain, result.commandId()));
    }

    private Optional<ControllerDomainResult> durableReplay(ControlCommandLedgerKey key) {
        return idempotencyLedger.lookup(key.idempotencyKey()).map(stored -> {
            if (stored.payloadFingerprint().equals(key.payloadFingerprint())) {
                return new ControllerDomainResult(
                        key.commandId(),
                        List.of(new ControlLogEmission("RESPONSE", stored.responseKey(), stored.responseValue())));
            }
            return new ControllerDomainResult(
                    key.commandId(),
                    List.of(new ControlLogEmission(
                            "RESPONSE",
                            key.commandId(),
                            idempotencyConflictResponse(key, stored.responseValue()))));
        });
    }

    private ControllerDomainResult withDurableLedgerEntry(
            Optional<ControlCommandLedgerKey> key,
            ControllerDomainResult result) {
        if (key.isEmpty()) {
            return result;
        }
        Optional<ControlLogEmission> response = result.emissions().stream()
                .filter(emission -> "RESPONSE".equals(emission.kind()))
                .findFirst();
        if (response.isEmpty()) {
            return result;
        }
        ControlCommandLedgerKey ledgerKey = key.orElseThrow();
        ControlLogEmission responseEmission = response.orElseThrow();
        DurableIdempotencyLedger.StoredDecision decision = new DurableIdempotencyLedger.StoredDecision(
                ledgerKey.idempotencyKey(),
                ledgerKey.payloadFingerprint(),
                responseEmission.key(),
                responseEmission.value());
        idempotencyLedger.put(decision);
        List<ControlLogEmission> emissions = new ArrayList<>(result.emissions());
        emissions.add(new ControlLogEmission(
                "STATE",
                "ctrl.idempotency." + domain + ":" + ledgerKey.idempotencyKey(),
                idempotencyLedger.encode(decision)));
        return new ControllerDomainResult(result.commandId(), emissions);
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

    private static String idempotencyConflictResponse(ControlCommandLedgerKey key, String storedResponse) {
        return "accepted=false"
                + "|revision=" + pipeField(storedResponse, "revision").orElse("0")
                + "|commandId=" + key.commandId()
                + "|reason=IDEMPOTENCY_CONFLICT"
                + "|traceId=" + key.traceId();
    }

    private static Optional<String> pipeField(String payload, String key) {
        for (String part : payload.split("\\|")) {
            int separator = part.indexOf('=');
            if (separator > 0 && part.substring(0, separator).equals(key)) {
                return Optional.of(part.substring(separator + 1));
            }
        }
        return Optional.empty();
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
