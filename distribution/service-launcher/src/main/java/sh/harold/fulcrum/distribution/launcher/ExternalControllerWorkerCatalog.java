package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.control.allocation.SharedShardAllocationBridge;
import sh.harold.fulcrum.control.allocation.SharedShardAllocationDecision;
import sh.harold.fulcrum.control.allocation.SharedShardAllocationEmission;
import sh.harold.fulcrum.control.allocation.SharedShardAllocationReceipt;
import sh.harold.fulcrum.control.allocation.SharedShardAllocationRejectionReason;
import sh.harold.fulcrum.control.allocation.SharedShardAllocationRequest;
import sh.harold.fulcrum.control.capability.CapabilityEnablementControlCommand;
import sh.harold.fulcrum.control.capability.CapabilityEnablementControlRecord;
import sh.harold.fulcrum.control.capability.CapabilityEnablementController;
import sh.harold.fulcrum.control.capability.CapabilityEnablementDecision;
import sh.harold.fulcrum.control.capability.CapabilityEnablementEmission;
import sh.harold.fulcrum.control.capability.CapabilityEnablementCommand;
import sh.harold.fulcrum.control.fault.FaultCommand;
import sh.harold.fulcrum.control.fault.FaultControlCommand;
import sh.harold.fulcrum.control.fault.FaultControlEmission;
import sh.harold.fulcrum.control.fault.FaultControlRecord;
import sh.harold.fulcrum.control.fault.FaultController;
import sh.harold.fulcrum.control.fault.FaultDecision;
import sh.harold.fulcrum.control.fault.FaultId;
import sh.harold.fulcrum.control.instance.SharedShardPlacementController;
import sh.harold.fulcrum.control.instance.SharedShardPlacementDecision;
import sh.harold.fulcrum.control.instance.SharedShardPlacementDecisionStatus;
import sh.harold.fulcrum.control.instance.SharedShardPlacementRequest;
import sh.harold.fulcrum.control.lifecycle.ExperienceSessionCommand;
import sh.harold.fulcrum.control.lifecycle.ExperienceSessionControlCommand;
import sh.harold.fulcrum.control.lifecycle.ExperienceSessionControlRecord;
import sh.harold.fulcrum.control.lifecycle.ExperienceSessionController;
import sh.harold.fulcrum.control.lifecycle.ExperienceSessionDecision;
import sh.harold.fulcrum.control.lifecycle.ExperienceSessionEmission;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceCommand;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceControlCommand;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceControlRecord;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceController;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceDecision;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceEmission;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceId;
import sh.harold.fulcrum.control.queue.QueuePartitionKey;
import sh.harold.fulcrum.control.queue.QueueRosterCommand;
import sh.harold.fulcrum.control.queue.QueueRosterControlCommand;
import sh.harold.fulcrum.control.queue.QueueRosterControlEmission;
import sh.harold.fulcrum.control.queue.QueueRosterControlRecord;
import sh.harold.fulcrum.control.queue.QueueRosterController;
import sh.harold.fulcrum.control.queue.QueueRosterDecision;
import sh.harold.fulcrum.control.route.RouteAttemptCommand;
import sh.harold.fulcrum.control.route.RouteAttemptControlCommand;
import sh.harold.fulcrum.control.route.RouteAttemptControlEmission;
import sh.harold.fulcrum.control.route.RouteAttemptControlRecord;
import sh.harold.fulcrum.control.route.RouteAttemptController;
import sh.harold.fulcrum.control.route.RouteAttemptDecision;
import sh.harold.fulcrum.control.route.RouteAttemptId;
import sh.harold.fulcrum.host.api.HostAllocationClaim;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class ExternalControllerWorkerCatalog {
    private final RuntimeExternalClients.ControllerClients clients;
    private final SharedShardAllocationBridge sharedShardAllocationBridge;
    private final SharedShardPlacementController sharedShardPlacementController = new SharedShardPlacementController();
    private final RouteAttemptController routeAttemptController = new RouteAttemptController();
    private final ExperienceSessionController experienceSessionController = new ExperienceSessionController();
    private final LifecycleTraceController lifecycleTraceController = new LifecycleTraceController();
    private final CapabilityEnablementController capabilityEnablementController = new CapabilityEnablementController();
    private final QueueRosterController queueRosterController = new QueueRosterController();
    private final FaultController faultController = new FaultController();
    private final Map<RouteAttemptId, RouteAttemptControlRecord> routeAttemptRecords = new HashMap<>();
    private final Map<SessionId, ExperienceSessionControlRecord> experienceSessionRecords = new HashMap<>();
    private final Map<LifecycleTraceId, LifecycleTraceControlRecord> lifecycleTraceRecords = new HashMap<>();
    private final Map<CapabilityScope, CapabilityEnablementControlRecord> capabilityEnablementRecords = new HashMap<>();
    private final Map<QueuePartitionKey, QueueRosterControlRecord> queueRosterRecords = new HashMap<>();
    private final Map<FaultId, FaultControlRecord> faultRecords = new HashMap<>();
    private final Map<String, StoredSharedShardPlacement> sharedShardPlacements = new HashMap<>();
    private final Map<SessionId, StoredSharedShardAllocation> sharedShardAllocations = new HashMap<>();
    private final long fencingEpoch;

    ExternalControllerWorkerCatalog(RuntimeExternalClients.ControllerClients clients, long fencingEpoch) {
        this.clients = Objects.requireNonNull(clients, "clients");
        this.sharedShardAllocationBridge = new SharedShardAllocationBridge(clients.allocationPort());
        if (fencingEpoch < 0) {
            throw new IllegalArgumentException("fencingEpoch must be non-negative");
        }
        this.fencingEpoch = fencingEpoch;
    }

    List<ControllerWorkerBinding> workerBindings() {
        return List.of(
                binding(ControllerWorkerCatalog.ROUTE_ATTEMPT, this::handleRouteAttempt, this::replayRouteAttemptState),
                binding(ControllerWorkerCatalog.EXPERIENCE_SESSION, this::handleExperienceSession, this::replayExperienceSessionState),
                binding(ControllerWorkerCatalog.LIFECYCLE_TRACE, this::handleLifecycleTrace, this::replayLifecycleTraceState),
                binding(ControllerWorkerCatalog.CAPABILITY_ENABLEMENT, this::handleCapabilityEnablement, this::replayCapabilityEnablementState),
                binding(ControllerWorkerCatalog.QUEUE_ROSTER, this::handleQueueRoster, this::replayQueueRosterState),
                binding(ControllerWorkerCatalog.FAULT, this::handleFault, this::replayFaultState),
                binding(ControllerWorkerCatalog.SHARED_SHARD_PLACEMENT, this::handleSharedShardPlacement, this::replaySharedShardPlacementState),
                binding(ControllerWorkerCatalog.SHARED_SHARD_ALLOCATION, this::handleSharedShardAllocation, this::replaySharedShardAllocationState));
    }

    private ControllerWorkerBinding binding(
            String domain,
            java.util.function.Function<ConsumerRecord<String, String>, ExternalControllerDomainWorker.ControllerDomainResult> handler,
            java.util.function.Consumer<ConsumerRecord<String, String>> stateReplayer) {
        return new ControllerWorkerBinding(domain, new ExternalControllerDomainWorker(clients, domain, handler, stateReplayer));
    }

    private ExternalControllerDomainWorker.ControllerDomainResult handleRouteAttempt(ConsumerRecord<String, String> record) {
        RouteAttemptControlCommand<? extends RouteAttemptCommand> command =
                ControlCommandWireCodec.decodeRouteAttemptCommand(record);
        RouteAttemptId id = command.envelope().payload().routeAttemptId();
        RouteAttemptControlRecord current =
                routeAttemptRecords.computeIfAbsent(id, ignored -> RouteAttemptController.emptyRecord(fencingEpoch));
        RouteAttemptDecision decision = routeAttemptController.handle(command, current);
        routeAttemptRecords.put(id, decision.record());
        return result(command.envelope().commandId().value(), routeEmissions(decision));
    }

    private ExternalControllerDomainWorker.ControllerDomainResult handleExperienceSession(ConsumerRecord<String, String> record) {
        ExperienceSessionControlCommand<? extends ExperienceSessionCommand> command =
                ControlCommandWireCodec.decodeExperienceSessionRequest(record);
        SessionId id = command.envelope().payload().sessionId();
        ExperienceSessionControlRecord current =
                experienceSessionRecords.computeIfAbsent(id, ignored -> ExperienceSessionController.emptyRecord(fencingEpoch));
        ExperienceSessionDecision decision = experienceSessionController.handle(command, current);
        experienceSessionRecords.put(id, decision.record());
        return result(command.envelope().commandId().value(), experienceSessionEmissions(decision));
    }

    private ExternalControllerDomainWorker.ControllerDomainResult handleLifecycleTrace(ConsumerRecord<String, String> record) {
        LifecycleTraceControlCommand<? extends LifecycleTraceCommand> command =
                ControlCommandWireCodec.decodeLifecycleTraceRecord(record);
        LifecycleTraceId id = command.envelope().payload().traceId();
        LifecycleTraceControlRecord current =
                lifecycleTraceRecords.computeIfAbsent(id, ignored -> LifecycleTraceController.emptyRecord(fencingEpoch, id));
        LifecycleTraceDecision decision = lifecycleTraceController.handle(command, current);
        lifecycleTraceRecords.put(id, decision.record());
        return result(command.envelope().commandId().value(), lifecycleTraceEmissions(decision));
    }

    private ExternalControllerDomainWorker.ControllerDomainResult handleCapabilityEnablement(ConsumerRecord<String, String> record) {
        CapabilityEnablementControlCommand<? extends CapabilityEnablementCommand> command =
                ControlCommandWireCodec.decodeCapabilityEnablement(record);
        CapabilityScope scope = command.envelope().payload().scope();
        CapabilityEnablementControlRecord current = capabilityEnablementRecords.computeIfAbsent(
                scope,
                ignored -> CapabilityEnablementControlRecord.empty(scope, fencingEpoch));
        CapabilityEnablementDecision decision = capabilityEnablementController.handle(command, current);
        capabilityEnablementRecords.put(scope, decision.record());
        return result(command.envelope().commandId().value(), capabilityEmissions(decision));
    }

    private ExternalControllerDomainWorker.ControllerDomainResult handleQueueRoster(ConsumerRecord<String, String> record) {
        QueueRosterControlCommand<? extends QueueRosterCommand> command =
                ControlCommandWireCodec.decodeQueueRosterSubmit(record);
        QueuePartitionKey partitionKey = command.envelope().payload().partitionKey();
        QueueRosterControlRecord current =
                queueRosterRecords.computeIfAbsent(partitionKey, ignored -> QueueRosterControlRecord.empty(fencingEpoch));
        QueueRosterDecision decision = queueRosterController.handle(command, current);
        queueRosterRecords.put(partitionKey, decision.record());
        return result(command.envelope().commandId().value(), queueEmissions(decision, partitionKey));
    }

    private ExternalControllerDomainWorker.ControllerDomainResult handleFault(ConsumerRecord<String, String> record) {
        FaultControlCommand<? extends FaultCommand> command = ControlCommandWireCodec.decodeFaultRecord(record);
        FaultId id = command.envelope().payload().faultId();
        FaultControlRecord current = faultRecords.computeIfAbsent(id, ignored -> FaultControlRecord.empty(fencingEpoch));
        FaultDecision decision = faultController.handle(command, current);
        faultRecords.put(id, decision.record());
        return result(command.envelope().commandId().value(), faultEmissions(decision));
    }

    private ExternalControllerDomainWorker.ControllerDomainResult handleSharedShardPlacement(ConsumerRecord<String, String> record) {
        SharedShardPlacementWireRequest wireRequest = ControlCommandWireCodec.decodeSharedShardPlacementRequest(record);
        SharedShardPlacementRequest request = wireRequest.request();
        String fingerprint = sharedShardPlacementFingerprint(wireRequest);
        StoredSharedShardPlacement stored = sharedShardPlacements.get(request.placementAttemptId());
        if (stored != null) {
            if (stored.requestFingerprint().equals(fingerprint)) {
                return result(request.placementAttemptId(), placementEmissions(stored));
            }
            return result(request.placementAttemptId(), List.of(new ControlLogEmission(
                    "RESPONSE",
                    request.placementAttemptId(),
                    placementConflictResponse(request))));
        }

        SharedShardPlacementDecision decision = sharedShardPlacementController.place(request, wireRequest.candidates());
        StoredSharedShardPlacement storedDecision =
                new StoredSharedShardPlacement(fingerprint, request, decision);
        sharedShardPlacements.put(request.placementAttemptId(), storedDecision);
        return result(request.placementAttemptId(), placementEmissions(storedDecision));
    }

    private ExternalControllerDomainWorker.ControllerDomainResult handleSharedShardAllocation(ConsumerRecord<String, String> record) {
        SharedShardAllocationRequest request = ControlCommandWireCodec.decodeSharedShardAllocationRequest(record);
        StoredSharedShardAllocation stored = sharedShardAllocations.get(request.sessionId());
        if (stored != null) {
            if (stored.requestFingerprint().equals(sharedShardAllocationFingerprint(request))) {
                SharedShardAllocationReceipt receipt =
                        SharedShardAllocationReceipt.accepted(stored.request(), stored.claim());
                return result(request.sessionId().value(), List.of(new ControlLogEmission(
                        "RESPONSE",
                        request.sessionId().value(),
                        receipt.wireValue())));
            }
            SharedShardAllocationDecision decision = SharedShardAllocationDecision.rejected(
                    request,
                    SharedShardAllocationRejectionReason.IDEMPOTENCY_CONFLICT);
            return result(request.sessionId().value(), allocationEmissions(decision.emissions()));
        }
        SharedShardAllocationDecision decision = sharedShardAllocationBridge.allocate(request);
        decision.claim().ifPresent(claim -> sharedShardAllocations.put(
                request.sessionId(),
                new StoredSharedShardAllocation(sharedShardAllocationFingerprint(request), request, claim)));
        return result(request.sessionId().value(), allocationEmissions(request, decision));
    }

    private void replayRouteAttemptState(ConsumerRecord<String, String> record) {
        if (ControllerStateWireCodec.isRecordType(record.value(), ControllerWorkerCatalog.ROUTE_ATTEMPT)) {
            RouteAttemptControlRecord replayed = ControllerStateWireCodec.decodeRouteAttempt(record.value());
            replayed.snapshot().ifPresent(snapshot -> routeAttemptRecords.put(snapshot.routeAttemptId(), replayed));
        }
    }

    private void replayExperienceSessionState(ConsumerRecord<String, String> record) {
        if (ControllerStateWireCodec.isRecordType(record.value(), ControllerWorkerCatalog.EXPERIENCE_SESSION)) {
            ExperienceSessionControlRecord replayed = ControllerStateWireCodec.decodeExperienceSession(record.value());
            replayed.sessionRecord().ifPresent(session -> experienceSessionRecords.put(session.sessionId(), replayed));
        }
    }

    private void replayLifecycleTraceState(ConsumerRecord<String, String> record) {
        if (ControllerStateWireCodec.isRecordType(record.value(), ControllerWorkerCatalog.LIFECYCLE_TRACE)) {
            LifecycleTraceControlRecord replayed = ControllerStateWireCodec.decodeLifecycleTrace(record.value());
            lifecycleTraceRecords.put(replayed.traceRecord().traceId(), replayed);
        }
    }

    private void replayCapabilityEnablementState(ConsumerRecord<String, String> record) {
        if (ControllerStateWireCodec.isRecordType(record.value(), ControllerWorkerCatalog.CAPABILITY_ENABLEMENT)) {
            CapabilityEnablementControlRecord replayed = ControllerStateWireCodec.decodeCapabilityEnablement(record.value());
            capabilityEnablementRecords.put(replayed.state().scope(), replayed);
        }
    }

    private void replayQueueRosterState(ConsumerRecord<String, String> record) {
        if (ControllerStateWireCodec.isRecordType(record.value(), ControllerWorkerCatalog.QUEUE_ROSTER)) {
            queueRosterRecords.put(queuePartitionKey(record.key()), ControllerStateWireCodec.decodeQueueRoster(record.value()));
        }
    }

    private void replayFaultState(ConsumerRecord<String, String> record) {
        if (ControllerStateWireCodec.isRecordType(record.value(), ControllerWorkerCatalog.FAULT)) {
            FaultControlRecord replayed = ControllerStateWireCodec.decodeFault(record.value());
            replayed.faultRecord().ifPresent(fault -> faultRecords.put(fault.faultId(), replayed));
        }
    }

    private void replaySharedShardPlacementState(ConsumerRecord<String, String> record) {
        if (ControllerStateWireCodec.isRecordType(record.value(), ControllerWorkerCatalog.SHARED_SHARD_PLACEMENT)) {
            StoredSharedShardPlacement replayed = ControllerStateWireCodec.decodeSharedShardPlacement(record.value());
            sharedShardPlacements.put(replayed.request().placementAttemptId(), replayed);
        }
    }

    private void replaySharedShardAllocationState(ConsumerRecord<String, String> record) {
        if (ControllerStateWireCodec.isRecordType(record.value(), ControllerWorkerCatalog.SHARED_SHARD_ALLOCATION)) {
            StoredSharedShardAllocation replayed = ControllerStateWireCodec.decodeSharedShardAllocation(record.value());
            sharedShardAllocations.put(replayed.request().sessionId(), replayed);
        }
    }

    private static ExternalControllerDomainWorker.ControllerDomainResult result(
            String commandId,
            List<ControlLogEmission> emissions) {
        return new ExternalControllerDomainWorker.ControllerDomainResult(commandId, emissions);
    }

    private static List<ControlLogEmission> routeEmissions(RouteAttemptDecision decision) {
        if (decision.emissions().isEmpty()) {
            return List.of(new ControlLogEmission("RESPONSE", decision.receipt().commandId(), decision.receipt().wireValue()));
        }
        return decision.emissions().stream()
                .map(emission -> new ControlLogEmission(
                        emission.kind().name(),
                        emission.key(),
                        stateValue(emission.kind().name(), emission.value(), ControllerStateWireCodec.encodeRouteAttempt(decision.record()))))
                .toList();
    }

    private static List<ControlLogEmission> experienceSessionEmissions(ExperienceSessionDecision decision) {
        if (decision.emissions().isEmpty()) {
            return List.of(new ControlLogEmission("RESPONSE", decision.receipt().commandId(), decision.receipt().wireValue()));
        }
        return decision.emissions().stream()
                .map(emission -> new ControlLogEmission(
                        emission.kind().name(),
                        emission.key(),
                        stateValue(emission.kind().name(), emission.value(), ControllerStateWireCodec.encodeExperienceSession(decision.record()))))
                .toList();
    }

    private static List<ControlLogEmission> lifecycleTraceEmissions(LifecycleTraceDecision decision) {
        if (decision.emissions().isEmpty()) {
            return List.of(new ControlLogEmission("RESPONSE", decision.receipt().commandId(), decision.receipt().wireValue()));
        }
        return decision.emissions().stream()
                .map(emission -> new ControlLogEmission(
                        emission.kind().name(),
                        emission.key(),
                        stateValue(emission.kind().name(), emission.value(), ControllerStateWireCodec.encodeLifecycleTrace(decision.record()))))
                .toList();
    }

    private static List<ControlLogEmission> capabilityEmissions(CapabilityEnablementDecision decision) {
        if (decision.emissions().isEmpty()) {
            return List.of(new ControlLogEmission("RESPONSE", decision.receipt().commandId(), decision.receipt().wireValue()));
        }
        return decision.emissions().stream()
                .map(emission -> new ControlLogEmission(
                        emission.kind().name(),
                        emission.key(),
                        stateValue(emission.kind().name(), emission.payload(), ControllerStateWireCodec.encodeCapabilityEnablement(decision.record()))))
                .toList();
    }

    private static List<ControlLogEmission> queueEmissions(QueueRosterDecision decision, QueuePartitionKey partitionKey) {
        if (decision.emissions().isEmpty()) {
            return List.of(new ControlLogEmission("RESPONSE", decision.receipt().commandId(), decision.receipt().wireValue()));
        }
        String encodedState = ControllerStateWireCodec.encodeQueueRoster(decision.record());
        return decision.emissions().stream()
                .map(emission -> new ControlLogEmission(
                        emission.kind().name(),
                        emission.key(),
                        stateValue(emission.kind().name(), emission.value(), encodedState)))
                .toList();
    }

    private static List<ControlLogEmission> faultEmissions(FaultDecision decision) {
        if (decision.emissions().isEmpty()) {
            return List.of(new ControlLogEmission("RESPONSE", decision.receipt().commandId(), decision.receipt().wireValue()));
        }
        return decision.emissions().stream()
                .map(emission -> new ControlLogEmission(
                        emission.kind().name(),
                        emission.key(),
                        stateValue(emission.kind().name(), emission.value(), ControllerStateWireCodec.encodeFault(decision.record()))))
                .toList();
    }

    private static List<ControlLogEmission> placementEmissions(StoredSharedShardPlacement placement) {
        String response = placementResponseValue(placement);
        String stateKey = "ctrl.state.shared-shard-placement:" + placement.request().placementAttemptId();
        List<ControlLogEmission> emissions = new java.util.ArrayList<>(List.of(
                new ControlLogEmission("EVENT", placement.request().placementAttemptId(), response),
                new ControlLogEmission("STATE", stateKey, ControllerStateWireCodec.encodeSharedShardPlacement(placement)),
                new ControlLogEmission("RESPONSE", placement.request().placementAttemptId(), response)));
        if (placement.decision().status() == SharedShardPlacementDecisionStatus.REQUEST_ALLOCATION) {
            SharedShardAllocationRequest allocationRequest = allocationRequestFromPlacement(placement.request());
            emissions.add(new ControlLogEmission(
                    "SHARED_SHARD_ALLOCATION_COMMAND",
                    allocationRequest.sessionId().value(),
                    ControlCommandWireCodec.encodeSharedShardAllocationRequest(allocationRequest)));
        }
        return List.copyOf(emissions);
    }

    private static String placementResponseValue(StoredSharedShardPlacement placement) {
        SharedShardPlacementDecision decision = placement.decision();
        SharedShardPlacementRequest request = placement.request();
        return "accepted=true"
                + "|status=" + decision.status().name()
                + "|placementAttemptId=" + request.placementAttemptId()
                + "|subjectId=" + request.subjectId().value()
                + "|presenceId=" + request.presenceId().value()
                + "|experienceId=" + request.experience().experienceId().value()
                + "|poolId=" + request.experience().poolId().value()
                + "|resolvedManifestId=" + request.experience().resolvedManifestId().value()
                + "|instanceId=" + decision.instanceId().map(value -> value.value()).orElse("none")
                + "|sessionId=" + decision.sessionId().map(value -> value.value()).orElse("none")
                + "|slotId=" + decision.slotId().map(value -> value.value()).orElse("none")
                + "|traceId=" + request.traceEnvelope().traceId();
    }

    private static String placementConflictResponse(SharedShardPlacementRequest request) {
        return "accepted=false"
                + "|status=REJECTED"
                + "|reason=IDEMPOTENCY_CONFLICT"
                + "|placementAttemptId=" + request.placementAttemptId()
                + "|subjectId=" + request.subjectId().value()
                + "|presenceId=" + request.presenceId().value()
                + "|traceId=" + request.traceEnvelope().traceId();
    }

    private static List<ControlLogEmission> allocationEmissions(List<SharedShardAllocationEmission> emissions) {
        return emissions.stream()
                .map(emission -> new ControlLogEmission(emission.kind().name(), emission.key(), emission.value()))
                .toList();
    }

    private static List<ControlLogEmission> allocationEmissions(
            SharedShardAllocationRequest request,
            SharedShardAllocationDecision decision) {
        List<ControlLogEmission> emissions = new java.util.ArrayList<>(allocationEmissions(decision.emissions()));
        decision.claim().ifPresent(claim -> emissions.add(new ControlLogEmission(
                "STATE",
                "ctrl.state.shared-shard-allocation:" + request.sessionId().value(),
                ControllerStateWireCodec.encodeSharedShardAllocation(new StoredSharedShardAllocation(
                        sharedShardAllocationFingerprint(request),
                        request,
                        claim)))));
        return List.copyOf(emissions);
    }

    private static String stateValue(String kind, String originalValue, String encodedState) {
        return "STATE".equals(kind) ? encodedState : originalValue;
    }

    private static QueuePartitionKey queuePartitionKey(String stateKey) {
        String prefix = "ctrl.state.queue-roster:";
        if (!stateKey.startsWith(prefix)) {
            throw new IllegalArgumentException("Unexpected queue-roster state key " + stateKey);
        }
        Map<String, String> fields = pipeFields(stateKey.substring(prefix.length()));
        return new QueuePartitionKey(
                new sh.harold.fulcrum.api.kernel.ExperienceId(requireField(fields, "experience")),
                "none".equals(fields.get("mode")) ? Optional.empty() : Optional.of(requireField(fields, "mode")),
                new sh.harold.fulcrum.api.kernel.PoolId(requireField(fields, "pool")));
    }

    private static Map<String, String> pipeFields(String payload) {
        Map<String, String> fields = new HashMap<>();
        for (String part : payload.split("\\|")) {
            int separator = part.indexOf('=');
            if (separator < 1) {
                throw new IllegalArgumentException("Malformed key field " + part);
            }
            fields.put(part.substring(0, separator), part.substring(separator + 1));
        }
        return fields;
    }

    private static String requireField(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing key field " + key);
        }
        return value;
    }

    private static String sharedShardAllocationFingerprint(SharedShardAllocationRequest request) {
        return request.experienceId().value()
                + "|" + request.poolId().value()
                + "|" + request.sessionId().value()
                + "|" + request.resolvedManifestId().value();
    }

    private static SharedShardAllocationRequest allocationRequestFromPlacement(SharedShardPlacementRequest request) {
        return new SharedShardAllocationRequest(
                request.experience().experienceId(),
                request.experience().poolId(),
                new SessionId("session-" + request.placementAttemptId()),
                request.experience().resolvedManifestId(),
                request.traceEnvelope().child(
                        "span-shared-shard-allocation-" + request.placementAttemptId(),
                        request.requestedAt()),
                request.requestedAt());
    }

    private static String sharedShardPlacementFingerprint(SharedShardPlacementWireRequest wireRequest) {
        SharedShardPlacementRequest request = wireRequest.request();
        return request.experience().experienceId().value()
                + "|" + request.experience().poolId().value()
                + "|" + request.experience().resolvedManifestId().value()
                + "|" + request.subjectId().value()
                + "|" + request.presenceId().value()
                + "|" + request.placementAttemptId()
                + "|" + request.capabilityScopeFingerprint().orElse("none")
                + "|" + wireRequest.candidates().stream()
                        .map(candidate -> candidate.instanceSnapshot().instanceId().value()
                                + ":" + candidate.occupancySnapshot().sessionId().value()
                                + ":" + candidate.occupancySnapshot().slotId().value()
                                + ":" + candidate.occupancySnapshot().currentPresences()
                                + ":" + candidate.occupancySnapshot().hardCapacity())
                        .collect(java.util.stream.Collectors.joining(","));
    }

    record StoredSharedShardPlacement(
            String requestFingerprint,
            SharedShardPlacementRequest request,
            SharedShardPlacementDecision decision) {
        StoredSharedShardPlacement {
            requestFingerprint = Objects.requireNonNull(requestFingerprint, "requestFingerprint");
            if (requestFingerprint.isBlank()) {
                throw new IllegalArgumentException("requestFingerprint must not be blank");
            }
            request = Objects.requireNonNull(request, "request");
            decision = Objects.requireNonNull(decision, "decision");
            if (decision.status() == SharedShardPlacementDecisionStatus.SELECTED_EXISTING_SESSION
                    && (decision.instanceId().isEmpty() || decision.sessionId().isEmpty() || decision.slotId().isEmpty())) {
                throw new IllegalArgumentException("selected shared-shard placement state requires Instance, Session, and Slot");
            }
        }
    }

    record StoredSharedShardAllocation(
            String requestFingerprint,
            SharedShardAllocationRequest request,
            HostAllocationClaim claim) {
        StoredSharedShardAllocation {
            requestFingerprint = Objects.requireNonNull(requestFingerprint, "requestFingerprint");
            if (requestFingerprint.isBlank()) {
                throw new IllegalArgumentException("requestFingerprint must not be blank");
            }
            request = Objects.requireNonNull(request, "request");
            claim = Objects.requireNonNull(claim, "claim");
        }
    }
}
