package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.control.instance.InstanceRegistryControlCommand;
import sh.harold.fulcrum.control.instance.InstanceRegistryController;
import sh.harold.fulcrum.control.instance.InstanceRegistryDecision;
import sh.harold.fulcrum.control.instance.InstanceRegistryEmission;
import sh.harold.fulcrum.control.instance.InstanceRegistryEmissionKind;
import sh.harold.fulcrum.control.instance.InstanceRegistryRecord;
import sh.harold.fulcrum.control.instance.RegisterInstance;
import sh.harold.fulcrum.data.store.kafka.KafkaClientBundle;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;

final class ExternalInstanceRegistryControllerWorker implements ControllerWorkerPoller {
    static final String DOMAIN = "instance-registry";
    static final String COMMAND_TOPIC = "ctrl.cmd." + DOMAIN;
    static final String EVENT_TOPIC = "ctrl.evt." + DOMAIN;
    static final String RESPONSE_TOPIC = "ctrl.rsp." + DOMAIN;

    private static final Duration POLL_TIMEOUT = Duration.ofMillis(100);

    private final RuntimeExternalClients.ControllerClients clients;
    private final KafkaClientBundle kafka;
    private final String stateTopic;
    private final long fencingEpoch;
    private final InstanceRegistryController controller = new InstanceRegistryController();
    private final Map<InstanceId, InstanceRegistryRecord> records = new HashMap<>();
    private final DurableIdempotencyLedger idempotencyLedger;
    private final Queue<ConsumerRecord<String, String>> pendingRecords = new ArrayDeque<>();
    private boolean subscribed;

    ExternalInstanceRegistryControllerWorker(RuntimeExternalClients.ControllerClients clients, long fencingEpoch) {
        this.clients = Objects.requireNonNull(clients, "clients");
        this.kafka = clients.controlKafka(DOMAIN);
        this.stateTopic = stateTopic(clients.settings().controlStateTopic());
        if (fencingEpoch < 0) {
            throw new IllegalArgumentException("fencingEpoch must be non-negative");
        }
        this.fencingEpoch = fencingEpoch;
        KafkaStateTopicReplayer.replay(kafka, stateTopic, POLL_TIMEOUT, this::replayState);
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
        Optional<ControlCommandLedgerKey> ledgerKey = InstanceRegistryControlWireCodec.commandLedgerKey(record);
        Optional<ControllerRuntimeReceipt> replay = ledgerKey.flatMap(this::publishDurableReplay);
        if (replay.isPresent()) {
            commit(record);
            return replay;
        }
        InstanceRegistryControlCommand<RegisterInstance> command =
                InstanceRegistryControlWireCodec.decodeRegisterCommand(record);
        InstanceId instanceId = command.envelope().payload().instanceId();
        InstanceRegistryRecord current = this.records.computeIfAbsent(
                instanceId,
                ignored -> InstanceRegistryController.emptyRecord(fencingEpoch));
        InstanceRegistryDecision decision = controller.handle(command, current);
        this.records.put(instanceId, decision.record());
        publish(decision);
        ledgerKey.ifPresent(key -> publishLedger(key, decision.receipt().commandId(), decision.receipt().wireValue()));
        commit(record);
        return Optional.of(new ControllerRuntimeReceipt(DOMAIN, command.envelope().commandId().value()));
    }

    private Optional<ControllerRuntimeReceipt> publishDurableReplay(ControlCommandLedgerKey key) {
        Optional<DurableIdempotencyLedger.StoredDecision> stored = idempotencyLedger.lookup(key.idempotencyKey());
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        DurableIdempotencyLedger.StoredDecision decision = stored.orElseThrow();
        if (decision.payloadFingerprint().equals(key.payloadFingerprint())) {
            kafka.producer().send(new ProducerRecord<>(RESPONSE_TOPIC, decision.responseKey(), decision.responseValue()));
        } else {
            kafka.producer().send(new ProducerRecord<>(
                    RESPONSE_TOPIC,
                    key.commandId(),
                    idempotencyConflictResponse(key, decision.responseValue())));
        }
        kafka.producer().flush();
        return Optional.of(new ControllerRuntimeReceipt(DOMAIN, key.commandId()));
    }

    private void replayState(ConsumerRecord<String, String> record) {
        if (ControllerStateWireCodec.isRecordType(record.value(), DOMAIN)) {
            InstanceRegistryRecord replayed = ControllerStateWireCodec.decodeInstanceRegistry(record.value());
            replayed.snapshot().ifPresent(snapshot -> records.put(snapshot.instanceId(), replayed));
        }
    }

    private void subscribeOnce() {
        if (!subscribed) {
            kafka.subscribe(List.of(COMMAND_TOPIC));
            subscribed = true;
        }
    }

    private void publish(InstanceRegistryDecision decision) {
        String encodedState = ControllerStateWireCodec.encodeInstanceRegistry(decision.record());
        if (decision.emissions().isEmpty()) {
            kafka.producer().send(new ProducerRecord<>(
                    RESPONSE_TOPIC,
                    decision.receipt().commandId(),
                    decision.receipt().wireValue()));
            kafka.producer().flush();
            return;
        }
        for (InstanceRegistryEmission emission : decision.emissions()) {
            String payload = emission.kind() == InstanceRegistryEmissionKind.STATE
                    ? encodedState
                    : emission.payload();
            kafka.producer().send(new ProducerRecord<>(topic(emission.kind()), emission.key(), payload));
        }
        kafka.producer().flush();
    }

    private void publishLedger(ControlCommandLedgerKey key, String responseKey, String responseValue) {
        DurableIdempotencyLedger.StoredDecision decision = new DurableIdempotencyLedger.StoredDecision(
                key.idempotencyKey(),
                key.payloadFingerprint(),
                responseKey,
                responseValue);
        idempotencyLedger.put(decision);
        kafka.producer().send(new ProducerRecord<>(
                stateTopic,
                "ctrl.idempotency." + DOMAIN + ":" + key.idempotencyKey(),
                idempotencyLedger.encode(decision)));
        kafka.producer().flush();
    }

    private void commit(ConsumerRecord<String, String> record) {
        kafka.consumer().commitSync(Map.of(
                new TopicPartition(record.topic(), record.partition()),
                new OffsetAndMetadata(record.offset() + 1)));
    }

    private String topic(InstanceRegistryEmissionKind kind) {
        return switch (kind) {
            case EVENT -> EVENT_TOPIC;
            case STATE, READY_INSTANCE, DRAINING_INSTANCE, OFFLINE_INSTANCE -> stateTopic;
            case RESPONSE -> RESPONSE_TOPIC;
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

    private static String stateTopic(String configuredTopic) {
        String checked = Objects.requireNonNull(configuredTopic, "configuredTopic").trim();
        if (checked.endsWith("." + DOMAIN)) {
            return checked;
        }
        return checked + "." + DOMAIN;
    }
}
