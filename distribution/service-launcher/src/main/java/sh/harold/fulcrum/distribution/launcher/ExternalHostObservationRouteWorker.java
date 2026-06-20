package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.control.lifecycle.ControlLifecycleNames;
import sh.harold.fulcrum.control.lifecycle.LifecyclePhase;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceControlCommand;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceId;
import sh.harold.fulcrum.control.lifecycle.RecordLifecycleObservation;
import sh.harold.fulcrum.control.route.AcknowledgeRouteAttempt;
import sh.harold.fulcrum.control.route.ControlRouteNames;
import sh.harold.fulcrum.control.route.ObserveHostAttach;
import sh.harold.fulcrum.control.route.RouteAttemptCommand;
import sh.harold.fulcrum.control.route.RouteAttemptControlCommand;
import sh.harold.fulcrum.control.route.RouteAttemptControlRecord;
import sh.harold.fulcrum.control.route.RouteAttemptId;
import sh.harold.fulcrum.control.route.RouteAttemptLifecycleStatus;
import sh.harold.fulcrum.control.route.RouteAttemptSnapshot;
import sh.harold.fulcrum.data.store.kafka.KafkaClientBundle;
import sh.harold.fulcrum.host.api.HostObservation;
import sh.harold.fulcrum.host.api.HostObservationTypes;
import sh.harold.fulcrum.host.api.HostObservationWireCodec;
import sh.harold.fulcrum.host.api.HostSecurityContext;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;

final class ExternalHostObservationRouteWorker implements ControllerWorkerPoller {
    static final String DOMAIN = "host-observation-route";
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(100);

    private final KafkaClientBundle kafka;
    private final HostSecurityContext securityContext;
    private final String hostObservationTopic;
    private final String routeCommandTopic;
    private final String routeStateTopic;
    private final String lifecycleTraceCommandTopic;
    private final Map<RouteObservationKey, RouteAttemptControlRecord> routeAttempts = new HashMap<>();
    private final Map<RouteObservationKey, HostObservation> pendingAttachObservations = new HashMap<>();
    private final Queue<ConsumerRecord<String, String>> pendingRecords = new ArrayDeque<>();
    private boolean subscribed;

    ExternalHostObservationRouteWorker(
            RuntimeExternalClients.ControllerClients clients,
            HostSecurityContext securityContext) {
        Objects.requireNonNull(clients, "clients");
        this.kafka = clients.hostObservationKafka();
        this.securityContext = Objects.requireNonNull(securityContext, "securityContext");
        this.hostObservationTopic = clients.settings().hostObservationTopic();
        this.routeCommandTopic = "ctrl.cmd." + ControllerWorkerCatalog.ROUTE_ATTEMPT;
        this.routeStateTopic = stateTopic(clients.settings().controlStateTopic(), ControllerWorkerCatalog.ROUTE_ATTEMPT);
        this.lifecycleTraceCommandTopic = "ctrl.cmd." + ControllerWorkerCatalog.LIFECYCLE_TRACE;
        KafkaStateTopicReplayer.replay(
                kafka,
                routeStateTopic,
                POLL_TIMEOUT,
                this::recordRouteState);
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
        if (routeStateTopic.equals(record.topic())) {
            recordRouteState(record);
            commit(record);
            return Optional.of(new ControllerRuntimeReceipt(DOMAIN, "route-state-" + record.offset()));
        }
        if (!hostObservationTopic.equals(record.topic())) {
            commit(record);
            return Optional.empty();
        }

        String handledKey = handleObservation(record)
                .orElse("ignored-observation-" + record.offset());
        commit(record);
        return Optional.of(new ControllerRuntimeReceipt(DOMAIN, handledKey));
    }

    private Optional<String> handleObservation(ConsumerRecord<String, String> record) {
        HostObservation observation = HostObservationWireCodec.decode(record.value());
        if (!HostObservationTypes.SESSION_ATTACHED.equals(observation.observationType())) {
            return Optional.empty();
        }
        RouteObservationKey key = RouteObservationKey.from(observation);
        RouteAttemptControlRecord current = routeAttempts.get(key);
        if (current == null || current.snapshot().isEmpty()) {
            pendingAttachObservations.put(key, observation);
            return Optional.empty();
        }
        RouteAttemptSnapshot snapshot = current.snapshot().orElseThrow();
        if (snapshot.status() != RouteAttemptLifecycleStatus.ISSUED_TO_HOST) {
            if (isAwaitingHostAttach(snapshot.status())) {
                pendingAttachObservations.put(key, observation);
            } else {
                pendingAttachObservations.remove(key);
            }
            return Optional.empty();
        }
        publishRouteProgress(current, snapshot, observation);
        pendingAttachObservations.remove(key);
        return Optional.of(snapshot.routeAttemptId().value());
    }

    private void publishRouteProgress(
            RouteAttemptControlRecord current,
            RouteAttemptSnapshot snapshot,
            HostObservation observation) {
        RouteAttemptId routeAttemptId = snapshot.routeAttemptId();
        Instant observedAt = observation.observedAt();
        RouteAttemptControlCommand<ObserveHostAttach> observed = routeCommand(
                new ObserveHostAttach(routeAttemptId, observedAt),
                ControlRouteNames.OBSERVE_HOST_ATTACH,
                "command-host-attach-observed-" + routeAttemptId.value(),
                "idem-host-attach-observed-" + routeAttemptId.value(),
                current.revision(),
                current.fencingEpoch(),
                observedAt,
                observation.traceEnvelope());
        RouteAttemptControlCommand<AcknowledgeRouteAttempt> acknowledged = routeCommand(
                new AcknowledgeRouteAttempt(routeAttemptId, observedAt),
                ControlRouteNames.ACKNOWLEDGE_ROUTE_ATTEMPT,
                "command-host-route-ack-" + routeAttemptId.value(),
                "idem-host-route-ack-" + routeAttemptId.value(),
                new Revision(current.revision().value() + 1),
                current.fencingEpoch(),
                observedAt,
                observation.traceEnvelope());
        String key = ControlRouteNames.aggregateId(routeAttemptId).value();
        kafka.producer().send(new ProducerRecord<>(
                routeCommandTopic,
                key,
                ControlCommandWireCodec.encodeRouteAttemptCommand(observed)));
        kafka.producer().send(new ProducerRecord<>(
                routeCommandTopic,
                key,
                ControlCommandWireCodec.encodeRouteAttemptCommand(acknowledged)));
        publishLifecycleProgress(current, snapshot, observation);
        kafka.producer().flush();
    }

    private void publishLifecycleProgress(
            RouteAttemptControlRecord current,
            RouteAttemptSnapshot snapshot,
            HostObservation observation) {
        sendLifecycleTrace(lifecycleTraceCommand(
                snapshot,
                LifecyclePhase.HOST_ATTACH_OBSERVED,
                "instance",
                observation.instanceId().value(),
                "host-attach",
                current.fencingEpoch(),
                observation.observedAt()));
        sendLifecycleTrace(lifecycleTraceCommand(
                snapshot,
                LifecyclePhase.SESSION_ACTIVE,
                "session",
                snapshot.sessionId().value(),
                "session-active",
                current.fencingEpoch(),
                observation.observedAt()));
    }

    private LifecycleTraceControlCommand<RecordLifecycleObservation> lifecycleTraceCommand(
            RouteAttemptSnapshot snapshot,
            LifecyclePhase phase,
            String aggregateType,
            String aggregateId,
            String commandSuffix,
            long fencingEpoch,
            Instant observedAt) {
        TraceEnvelope routeTrace = snapshot.traceEnvelope();
        LifecycleTraceId traceId = new LifecycleTraceId(routeTrace.traceId());
        TraceEnvelope commandTrace = routeTrace.child(
                "span-lifecycle-" + commandSuffix + "-" + compact(snapshot.routeAttemptId()),
                observedAt);
        RecordLifecycleObservation payload = new RecordLifecycleObservation(
                traceId,
                phase,
                aggregateType,
                aggregateId,
                Optional.of(snapshot.sessionId()),
                Optional.of(snapshot.targetResolvedManifestId()),
                observedAt,
                commandTrace);
        PrincipalId principal = securityContext.identity().principalId();
        return new LifecycleTraceControlCommand<>(
                new CommandEnvelope<>(
                        new CommandId("command-lifecycle-" + commandSuffix + "-" + snapshot.routeAttemptId().value()),
                        new IdempotencyKey("idem-lifecycle-" + commandSuffix + "-" + snapshot.routeAttemptId().value()),
                        principal,
                        ControlLifecycleNames.traceAggregateId(traceId),
                        ControlLifecycleNames.TRACE_CONTRACT,
                        ControlLifecycleNames.RECORD_LIFECYCLE_OBSERVATION,
                        commandTrace,
                        Optional.empty(),
                        payload),
                principal,
                fencingEpoch,
                Optional.empty(),
                "lifecycle-trace|phase=" + phase.name()
                        + "|traceId=" + traceId.value()
                        + "|routeAttemptId=" + snapshot.routeAttemptId().value()
                        + "|aggregateType=" + aggregateType
                        + "|aggregateId=" + aggregateId,
                observedAt);
    }

    private void sendLifecycleTrace(LifecycleTraceControlCommand<RecordLifecycleObservation> command) {
        kafka.producer().send(new ProducerRecord<>(
                lifecycleTraceCommandTopic,
                ControlLifecycleNames.traceAggregateId(command.envelope().payload().traceId()).value(),
                ControlCommandWireCodec.encodeLifecycleTraceRecord(command)));
    }

    private <T extends RouteAttemptCommand> RouteAttemptControlCommand<T> routeCommand(
            T payload,
            CommandName commandName,
            String commandId,
            String idempotencyKey,
            Revision expectedRevision,
            long fencingEpoch,
            Instant receivedAt,
            TraceEnvelope trace) {
        PrincipalId principal = securityContext.identity().principalId();
        return new RouteAttemptControlCommand<>(
                new CommandEnvelope<>(
                        new CommandId(commandId),
                        new IdempotencyKey(idempotencyKey),
                        principal,
                        ControlRouteNames.aggregateId(payload.routeAttemptId()),
                        ControlRouteNames.CONTRACT,
                        commandName,
                        trace,
                        Optional.empty(),
                        payload),
                principal,
                fencingEpoch,
                Optional.of(expectedRevision),
                payloadFingerprint(commandName, payload, receivedAt),
                receivedAt);
    }

    private void recordRouteState(ConsumerRecord<String, String> record) {
        if (!ControllerStateWireCodec.isRecordType(record.value(), ControllerWorkerCatalog.ROUTE_ATTEMPT)) {
            return;
        }
        RouteAttemptControlRecord current = ControllerStateWireCodec.decodeRouteAttempt(record.value());
        current.snapshot().ifPresent(snapshot -> {
            for (SubjectId subjectId : snapshot.subjectIds()) {
                RouteObservationKey key = new RouteObservationKey(snapshot.routeId(), snapshot.sessionId(), subjectId);
                routeAttempts.put(key, current);
                HostObservation pending = pendingAttachObservations.get(key);
                if (pending != null && snapshot.status() == RouteAttemptLifecycleStatus.ISSUED_TO_HOST) {
                    publishRouteProgress(current, snapshot, pending);
                    pendingAttachObservations.remove(key);
                } else if (pending != null && !isAwaitingHostAttach(snapshot.status())) {
                    pendingAttachObservations.remove(key);
                }
            }
        });
    }

    private static boolean isAwaitingHostAttach(RouteAttemptLifecycleStatus status) {
        return status == RouteAttemptLifecycleStatus.CREATED
                || status == RouteAttemptLifecycleStatus.ISSUED_TO_PROXY
                || status == RouteAttemptLifecycleStatus.ISSUED_TO_HOST;
    }

    private void subscribeOnce() {
        if (!subscribed) {
            kafka.subscribe(List.of(hostObservationTopic, routeStateTopic));
            subscribed = true;
        }
    }

    private void commit(ConsumerRecord<String, String> record) {
        kafka.consumer().commitSync(Map.of(
                new TopicPartition(record.topic(), record.partition()),
                new OffsetAndMetadata(record.offset() + 1)));
    }

    private static String stateTopic(String configuredTopic, String domain) {
        String checked = requireNonBlank(configuredTopic, "configuredTopic");
        if (checked.endsWith("." + domain)) {
            return checked;
        }
        return checked + "." + domain;
    }

    private static String payloadFingerprint(
            CommandName commandName,
            RouteAttemptCommand payload,
            Instant receivedAt) {
        return "host-observation-route"
                + "|commandName=" + commandName.value()
                + "|routeAttemptId=" + payload.routeAttemptId().value()
                + "|receivedAt=" + receivedAt;
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    private static String compact(RouteAttemptId routeAttemptId) {
        return routeAttemptId.value().replace("-", "");
    }
}

record RouteObservationKey(RouteId routeId, SessionId sessionId, SubjectId subjectId) {
    RouteObservationKey {
        routeId = Objects.requireNonNull(routeId, "routeId");
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
    }

    static RouteObservationKey from(HostObservation observation) {
        Map<String, String> attributes = observation.attributes();
        return new RouteObservationKey(
                new RouteId(required(attributes, "routeId")),
                new SessionId(required(attributes, "sessionId")),
                new SubjectId(UUID.fromString(required(attributes, "subjectId"))));
    }

    private static String required(Map<String, String> attributes, String key) {
        String value = attributes.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing host observation attribute " + key);
        }
        return value;
    }
}
