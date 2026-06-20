package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.route.contract.RouteCommand;
import sh.harold.fulcrum.data.route.contract.RouteContracts;
import sh.harold.fulcrum.data.store.kafka.KafkaClientBundle;
import sh.harold.fulcrum.host.api.HostSecurityContext;
import sh.harold.fulcrum.host.velocity.VelocityBackendEndpoint;
import sh.harold.fulcrum.host.velocity.VelocityProxyRouteCommand;
import sh.harold.fulcrum.host.velocity.VelocityRouteBridgeRequest;
import sh.harold.fulcrum.host.velocity.VelocityRouteCommandFactory;
import sh.harold.fulcrum.host.velocity.VelocityRouteTransfer;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;

final class ExternalVelocityRouteWorker {
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(100);

    private final RuntimeExternalClients.VelocityClients clients;
    private final KafkaClientBundle kafka;
    private final HostSecurityContext securityContext;
    private final VelocityRouteCommandFactory routeCommandFactory;
    private final VelocitySharedShardAllocationRegistry allocations;
    private final String proxyRouteCommandTopic;
    private final String routeCommandTopic;
    private final String sharedShardAllocationStateTopic;
    private final Queue<ConsumerRecord<String, String>> pendingRecords = new ArrayDeque<>();
    private boolean subscribed;

    ExternalVelocityRouteWorker(
            RuntimeExternalClients.VelocityClients clients,
            HostSecurityContext securityContext,
            VelocitySharedShardAllocationRegistry allocations) {
        this.clients = Objects.requireNonNull(clients, "clients");
        this.kafka = clients.velocityKafka();
        this.securityContext = Objects.requireNonNull(securityContext, "securityContext");
        this.allocations = Objects.requireNonNull(allocations, "allocations");
        this.proxyRouteCommandTopic = clients.settings().proxyRouteCommandTopic();
        this.routeCommandTopic = clients.settings().routeCommandTopic();
        this.sharedShardAllocationStateTopic = clients.settings().sharedShardAllocationStateTopic();
        this.routeCommandFactory = new VelocityRouteCommandFactory(securityContext, routeCommandTopic);
        KafkaStateTopicReplayer.replay(
                kafka,
                sharedShardAllocationStateTopic,
                POLL_TIMEOUT,
                this::recordAllocationState);
    }

    Optional<VelocityRouteWorkerReceipt> handleNext() {
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
        if (sharedShardAllocationStateTopic.equals(record.topic())) {
            recordAllocationState(record);
            commit(record);
            return Optional.of(new VelocityRouteWorkerReceipt("allocation-state", false));
        }
        if (!proxyRouteCommandTopic.equals(record.topic())) {
            commit(record);
            return Optional.empty();
        }

        VelocityProxyRouteCommand command = VelocityProxyRouteCommand.parse(record.value());
        VelocityBackendEndpoint endpoint = backend(command.targetInstanceId());
        Optional<VelocityRouteTransfer> transfer = clients.routeBridgeClient().execute(
                new VelocityRouteBridgeRequest(command, endpoint));
        transfer.ifPresent(value -> {
            publishRouteAcknowledgement(command, value);
            allocations.recordRoutedSubject(value.targetSessionId(), value.subjectId());
        });
        commit(record);
        return Optional.of(new VelocityRouteWorkerReceipt(command.routeAttemptId(), transfer.isPresent()));
    }

    private VelocityBackendEndpoint backend(InstanceId targetInstanceId) {
        return allocations.backend(targetInstanceId);
    }

    private void recordAllocationState(ConsumerRecord<String, String> record) {
        if (!ControllerStateWireCodec.isRecordType(record.value(), ControllerWorkerCatalog.SHARED_SHARD_ALLOCATION)) {
            return;
        }
        ExternalControllerWorkerCatalog.StoredSharedShardAllocation allocation =
                ControllerStateWireCodec.decodeSharedShardAllocation(record.value());
        allocations.record(allocation);
    }

    private void publishRouteAcknowledgement(
            VelocityProxyRouteCommand proxyCommand,
            VelocityRouteTransfer transfer) {
        CommandEnvelope<RouteCommand> envelope = routeCommandFactory.acknowledgeRoute(
                new CommandId("command-velocity-ack-" + proxyCommand.routeAttemptId()),
                new IdempotencyKey("idem-velocity-ack-" + proxyCommand.routeAttemptId()),
                new TraceEnvelope(
                        proxyCommand.traceId(),
                        "span-velocity-route-" + proxyCommand.routeAttemptId(),
                        Optional.empty(),
                        transfer.acknowledgedAt(),
                        "velocity-route-worker",
                        securityContext.identity().instanceId()),
                transfer);
        AuthorityCommand<RouteCommand> command = new AuthorityCommand<>(
                envelope,
                securityContext.identity().principalId(),
                1,
                Optional.empty(),
                payloadFingerprint(transfer),
                transfer.acknowledgedAt());
        kafka.producer().send(new ProducerRecord<>(
                routeCommandTopic,
                RouteContracts.aggregateId(transfer.routeId()).value(),
                RouteAuthorityWireCodec.encodeCommand(command)));
        kafka.producer().flush();
    }

    private void subscribeOnce() {
        if (!subscribed) {
            kafka.subscribe(List.of(proxyRouteCommandTopic, sharedShardAllocationStateTopic));
            subscribed = true;
        }
    }

    private void commit(ConsumerRecord<String, String> record) {
        kafka.consumer().commitSync(Map.of(
                new TopicPartition(record.topic(), record.partition()),
                new OffsetAndMetadata(record.offset() + 1)));
    }

    private static String payloadFingerprint(VelocityRouteTransfer transfer) {
        return "velocity-ack|routeId=" + transfer.routeId().value()
                + "|subjectId=" + transfer.subjectId().value()
                + "|sessionId=" + transfer.targetSessionId().value()
                + "|targetInstanceId=" + transfer.targetInstanceId().value()
                + "|acknowledgedAt=" + transfer.acknowledgedAt();
    }
}

record VelocityRouteWorkerReceipt(String key, boolean transferred) {
    VelocityRouteWorkerReceipt {
        key = Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
    }
}
