package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.control.allocation.SharedShardAllocationDecision;
import sh.harold.fulcrum.control.allocation.SharedShardAllocationEmission;
import sh.harold.fulcrum.control.allocation.SharedShardAllocationRequest;
import sh.harold.fulcrum.control.capability.CapabilityEnablementCommand;
import sh.harold.fulcrum.control.capability.CapabilityEnablementControlCommand;
import sh.harold.fulcrum.control.capability.CapabilityEnablementControlRecord;
import sh.harold.fulcrum.control.capability.CapabilityEnablementDecision;
import sh.harold.fulcrum.control.capability.CapabilityEnablementEmission;
import sh.harold.fulcrum.control.fault.FaultCommand;
import sh.harold.fulcrum.control.fault.FaultControlCommand;
import sh.harold.fulcrum.control.fault.FaultControlRecord;
import sh.harold.fulcrum.control.fault.FaultDecision;
import sh.harold.fulcrum.control.fault.FaultId;
import sh.harold.fulcrum.control.fault.FaultControlEmission;
import sh.harold.fulcrum.control.instance.InstanceRegistryCommand;
import sh.harold.fulcrum.control.instance.InstanceRegistryControlCommand;
import sh.harold.fulcrum.control.instance.InstanceRegistryDecision;
import sh.harold.fulcrum.control.instance.InstanceRegistryEmission;
import sh.harold.fulcrum.control.instance.InstanceRegistryRecord;
import sh.harold.fulcrum.control.instance.SharedShardPlacementCandidate;
import sh.harold.fulcrum.control.instance.SharedShardPlacementDecision;
import sh.harold.fulcrum.control.instance.SharedShardPlacementRequest;
import sh.harold.fulcrum.control.lifecycle.ExperienceSessionCommand;
import sh.harold.fulcrum.control.lifecycle.ExperienceSessionControlCommand;
import sh.harold.fulcrum.control.lifecycle.ExperienceSessionControlRecord;
import sh.harold.fulcrum.control.lifecycle.ExperienceSessionDecision;
import sh.harold.fulcrum.control.lifecycle.ExperienceSessionEmission;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceCommand;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceControlCommand;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceControlRecord;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceDecision;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceEmission;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceId;
import sh.harold.fulcrum.control.queue.QueuePartitionKey;
import sh.harold.fulcrum.control.queue.QueueRosterCommand;
import sh.harold.fulcrum.control.queue.QueueRosterControlCommand;
import sh.harold.fulcrum.control.queue.QueueRosterControlEmission;
import sh.harold.fulcrum.control.queue.QueueRosterControlRecord;
import sh.harold.fulcrum.control.queue.QueueRosterDecision;
import sh.harold.fulcrum.control.route.RouteAttemptCommand;
import sh.harold.fulcrum.control.route.RouteAttemptControlCommand;
import sh.harold.fulcrum.control.route.RouteAttemptControlEmission;
import sh.harold.fulcrum.control.route.RouteAttemptControlRecord;
import sh.harold.fulcrum.control.route.RouteAttemptDecision;
import sh.harold.fulcrum.control.route.RouteAttemptId;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

final class LocalControllerRuntimeBindings {
    private final Queue<InstanceRegistryControlCommand<? extends InstanceRegistryCommand>> instanceRegistryCommands =
            new ConcurrentLinkedQueue<>();
    private final Map<InstanceId, InstanceRegistryRecord> instanceRegistryRecords = new ConcurrentHashMap<>();
    private final Queue<InstanceRegistryDecision> instanceRegistryDecisions = new ConcurrentLinkedQueue<>();
    private final Queue<InstanceRegistryEmission> instanceRegistryEmissions = new ConcurrentLinkedQueue<>();

    private final Queue<RouteAttemptControlCommand<? extends RouteAttemptCommand>> routeAttemptCommands =
            new ConcurrentLinkedQueue<>();
    private final Map<RouteAttemptId, RouteAttemptControlRecord> routeAttemptRecords = new ConcurrentHashMap<>();
    private final Queue<RouteAttemptDecision> routeAttemptDecisions = new ConcurrentLinkedQueue<>();
    private final Queue<RouteAttemptControlEmission> routeAttemptEmissions = new ConcurrentLinkedQueue<>();

    private final Queue<ExperienceSessionControlCommand<? extends ExperienceSessionCommand>> experienceSessionCommands =
            new ConcurrentLinkedQueue<>();
    private final Map<SessionId, ExperienceSessionControlRecord> experienceSessionRecords = new ConcurrentHashMap<>();
    private final Queue<ExperienceSessionDecision> experienceSessionDecisions = new ConcurrentLinkedQueue<>();
    private final Queue<ExperienceSessionEmission> experienceSessionEmissions = new ConcurrentLinkedQueue<>();

    private final Queue<LifecycleTraceControlCommand<? extends LifecycleTraceCommand>> lifecycleTraceCommands =
            new ConcurrentLinkedQueue<>();
    private final Map<LifecycleTraceId, LifecycleTraceControlRecord> lifecycleTraceRecords = new ConcurrentHashMap<>();
    private final Queue<LifecycleTraceDecision> lifecycleTraceDecisions = new ConcurrentLinkedQueue<>();
    private final Queue<LifecycleTraceEmission> lifecycleTraceEmissions = new ConcurrentLinkedQueue<>();

    private final Queue<CapabilityEnablementControlCommand<? extends CapabilityEnablementCommand>> capabilityEnablementCommands =
            new ConcurrentLinkedQueue<>();
    private final Map<CapabilityScope, CapabilityEnablementControlRecord> capabilityEnablementRecords =
            new ConcurrentHashMap<>();
    private final Queue<CapabilityEnablementDecision> capabilityEnablementDecisions = new ConcurrentLinkedQueue<>();
    private final Queue<CapabilityEnablementEmission> capabilityEnablementEmissions = new ConcurrentLinkedQueue<>();

    private final Queue<QueueRosterControlCommand<? extends QueueRosterCommand>> queueRosterCommands =
            new ConcurrentLinkedQueue<>();
    private final Map<QueuePartitionKey, QueueRosterControlRecord> queueRosterRecords = new ConcurrentHashMap<>();
    private final Queue<QueueRosterDecision> queueRosterDecisions = new ConcurrentLinkedQueue<>();
    private final Queue<QueueRosterControlEmission> queueRosterEmissions = new ConcurrentLinkedQueue<>();

    private final Queue<FaultControlCommand<? extends FaultCommand>> faultCommands = new ConcurrentLinkedQueue<>();
    private final Map<FaultId, FaultControlRecord> faultRecords = new ConcurrentHashMap<>();
    private final Queue<FaultDecision> faultDecisions = new ConcurrentLinkedQueue<>();
    private final Queue<FaultControlEmission> faultEmissions = new ConcurrentLinkedQueue<>();

    private final Queue<SharedShardAllocationRequest> sharedShardAllocationRequests = new ConcurrentLinkedQueue<>();
    private final Queue<SharedShardAllocationDecision> sharedShardAllocationDecisions = new ConcurrentLinkedQueue<>();
    private final Queue<SharedShardAllocationEmission> sharedShardAllocationEmissions = new ConcurrentLinkedQueue<>();

    private final Queue<SharedShardPlacementWork> sharedShardPlacementRequests = new ConcurrentLinkedQueue<>();
    private final Queue<SharedShardPlacementDecision> sharedShardPlacementDecisions = new ConcurrentLinkedQueue<>();

    private final Map<String, Queue<ControllerRuntimeReceipt>> committedReceipts = new ConcurrentHashMap<>();

    void enqueueInstanceRegistry(InstanceRegistryControlCommand<? extends InstanceRegistryCommand> command) {
        instanceRegistryCommands.add(Objects.requireNonNull(command, "command"));
    }

    Optional<InstanceRegistryControlCommand<? extends InstanceRegistryCommand>> pollInstanceRegistry() {
        return Optional.ofNullable(instanceRegistryCommands.poll());
    }

    InstanceRegistryRecord loadInstanceRegistryRecord(InstanceId instanceId, long fencingEpoch) {
        return instanceRegistryRecords.computeIfAbsent(
                Objects.requireNonNull(instanceId, "instanceId"),
                ignored -> InstanceRegistryRecord.empty(fencingEpoch));
    }

    void storeInstanceRegistryRecord(InstanceId instanceId, InstanceRegistryRecord record) {
        instanceRegistryRecords.put(
                Objects.requireNonNull(instanceId, "instanceId"),
                Objects.requireNonNull(record, "record"));
    }

    Optional<InstanceRegistryRecord> storedInstanceRegistryRecord(InstanceId instanceId) {
        return Optional.ofNullable(instanceRegistryRecords.get(Objects.requireNonNull(instanceId, "instanceId")));
    }

    void recordInstanceRegistryDecision(InstanceRegistryDecision decision) {
        instanceRegistryDecisions.add(Objects.requireNonNull(decision, "decision"));
    }

    List<InstanceRegistryDecision> instanceRegistryDecisions() {
        return List.copyOf(instanceRegistryDecisions);
    }

    void publishInstanceRegistryEmissions(List<InstanceRegistryEmission> emissions) {
        instanceRegistryEmissions.addAll(List.copyOf(Objects.requireNonNull(emissions, "emissions")));
    }

    List<InstanceRegistryEmission> instanceRegistryEmissions() {
        return List.copyOf(instanceRegistryEmissions);
    }

    void enqueueRouteAttempt(RouteAttemptControlCommand<? extends RouteAttemptCommand> command) {
        routeAttemptCommands.add(Objects.requireNonNull(command, "command"));
    }

    Optional<RouteAttemptControlCommand<? extends RouteAttemptCommand>> pollRouteAttempt() {
        return Optional.ofNullable(routeAttemptCommands.poll());
    }

    RouteAttemptControlRecord loadRouteAttemptRecord(RouteAttemptId routeAttemptId, long fencingEpoch) {
        return routeAttemptRecords.computeIfAbsent(
                Objects.requireNonNull(routeAttemptId, "routeAttemptId"),
                ignored -> RouteAttemptControlRecord.empty(fencingEpoch));
    }

    void storeRouteAttemptRecord(RouteAttemptId routeAttemptId, RouteAttemptControlRecord record) {
        routeAttemptRecords.put(
                Objects.requireNonNull(routeAttemptId, "routeAttemptId"),
                Objects.requireNonNull(record, "record"));
    }

    Optional<RouteAttemptControlRecord> storedRouteAttemptRecord(RouteAttemptId routeAttemptId) {
        return Optional.ofNullable(routeAttemptRecords.get(Objects.requireNonNull(routeAttemptId, "routeAttemptId")));
    }

    void recordRouteAttemptDecision(RouteAttemptDecision decision) {
        routeAttemptDecisions.add(Objects.requireNonNull(decision, "decision"));
    }

    List<RouteAttemptDecision> routeAttemptDecisions() {
        return List.copyOf(routeAttemptDecisions);
    }

    void publishRouteAttemptEmissions(List<RouteAttemptControlEmission> emissions) {
        routeAttemptEmissions.addAll(List.copyOf(Objects.requireNonNull(emissions, "emissions")));
    }

    List<RouteAttemptControlEmission> routeAttemptEmissions() {
        return List.copyOf(routeAttemptEmissions);
    }

    void enqueueExperienceSession(ExperienceSessionControlCommand<? extends ExperienceSessionCommand> command) {
        experienceSessionCommands.add(Objects.requireNonNull(command, "command"));
    }

    Optional<ExperienceSessionControlCommand<? extends ExperienceSessionCommand>> pollExperienceSession() {
        return Optional.ofNullable(experienceSessionCommands.poll());
    }

    ExperienceSessionControlRecord loadExperienceSessionRecord(SessionId sessionId, long fencingEpoch) {
        return experienceSessionRecords.computeIfAbsent(
                Objects.requireNonNull(sessionId, "sessionId"),
                ignored -> ExperienceSessionControlRecord.empty(fencingEpoch));
    }

    void storeExperienceSessionRecord(SessionId sessionId, ExperienceSessionControlRecord record) {
        experienceSessionRecords.put(
                Objects.requireNonNull(sessionId, "sessionId"),
                Objects.requireNonNull(record, "record"));
    }

    Optional<ExperienceSessionControlRecord> storedExperienceSessionRecord(SessionId sessionId) {
        return Optional.ofNullable(experienceSessionRecords.get(Objects.requireNonNull(sessionId, "sessionId")));
    }

    void recordExperienceSessionDecision(ExperienceSessionDecision decision) {
        experienceSessionDecisions.add(Objects.requireNonNull(decision, "decision"));
    }

    List<ExperienceSessionDecision> experienceSessionDecisions() {
        return List.copyOf(experienceSessionDecisions);
    }

    void publishExperienceSessionEmissions(List<ExperienceSessionEmission> emissions) {
        experienceSessionEmissions.addAll(List.copyOf(Objects.requireNonNull(emissions, "emissions")));
    }

    List<ExperienceSessionEmission> experienceSessionEmissions() {
        return List.copyOf(experienceSessionEmissions);
    }

    void enqueueLifecycleTrace(LifecycleTraceControlCommand<? extends LifecycleTraceCommand> command) {
        lifecycleTraceCommands.add(Objects.requireNonNull(command, "command"));
    }

    Optional<LifecycleTraceControlCommand<? extends LifecycleTraceCommand>> pollLifecycleTrace() {
        return Optional.ofNullable(lifecycleTraceCommands.poll());
    }

    LifecycleTraceControlRecord loadLifecycleTraceRecord(LifecycleTraceId traceId, long fencingEpoch) {
        return lifecycleTraceRecords.computeIfAbsent(
                Objects.requireNonNull(traceId, "traceId"),
                ignored -> LifecycleTraceControlRecord.empty(fencingEpoch, traceId));
    }

    void storeLifecycleTraceRecord(LifecycleTraceId traceId, LifecycleTraceControlRecord record) {
        lifecycleTraceRecords.put(
                Objects.requireNonNull(traceId, "traceId"),
                Objects.requireNonNull(record, "record"));
    }

    Optional<LifecycleTraceControlRecord> storedLifecycleTraceRecord(LifecycleTraceId traceId) {
        return Optional.ofNullable(lifecycleTraceRecords.get(Objects.requireNonNull(traceId, "traceId")));
    }

    void recordLifecycleTraceDecision(LifecycleTraceDecision decision) {
        lifecycleTraceDecisions.add(Objects.requireNonNull(decision, "decision"));
    }

    List<LifecycleTraceDecision> lifecycleTraceDecisions() {
        return List.copyOf(lifecycleTraceDecisions);
    }

    void publishLifecycleTraceEmissions(List<LifecycleTraceEmission> emissions) {
        lifecycleTraceEmissions.addAll(List.copyOf(Objects.requireNonNull(emissions, "emissions")));
    }

    List<LifecycleTraceEmission> lifecycleTraceEmissions() {
        return List.copyOf(lifecycleTraceEmissions);
    }

    void enqueueCapabilityEnablement(CapabilityEnablementControlCommand<? extends CapabilityEnablementCommand> command) {
        capabilityEnablementCommands.add(Objects.requireNonNull(command, "command"));
    }

    Optional<CapabilityEnablementControlCommand<? extends CapabilityEnablementCommand>> pollCapabilityEnablement() {
        return Optional.ofNullable(capabilityEnablementCommands.poll());
    }

    CapabilityEnablementControlRecord loadCapabilityEnablementRecord(CapabilityScope scope, long fencingEpoch) {
        return capabilityEnablementRecords.computeIfAbsent(
                Objects.requireNonNull(scope, "scope"),
                ignored -> CapabilityEnablementControlRecord.empty(scope, fencingEpoch));
    }

    void storeCapabilityEnablementRecord(CapabilityScope scope, CapabilityEnablementControlRecord record) {
        capabilityEnablementRecords.put(
                Objects.requireNonNull(scope, "scope"),
                Objects.requireNonNull(record, "record"));
    }

    Optional<CapabilityEnablementControlRecord> storedCapabilityEnablementRecord(CapabilityScope scope) {
        return Optional.ofNullable(capabilityEnablementRecords.get(Objects.requireNonNull(scope, "scope")));
    }

    void recordCapabilityEnablementDecision(CapabilityEnablementDecision decision) {
        capabilityEnablementDecisions.add(Objects.requireNonNull(decision, "decision"));
    }

    List<CapabilityEnablementDecision> capabilityEnablementDecisions() {
        return List.copyOf(capabilityEnablementDecisions);
    }

    void publishCapabilityEnablementEmissions(List<CapabilityEnablementEmission> emissions) {
        capabilityEnablementEmissions.addAll(List.copyOf(Objects.requireNonNull(emissions, "emissions")));
    }

    List<CapabilityEnablementEmission> capabilityEnablementEmissions() {
        return List.copyOf(capabilityEnablementEmissions);
    }

    void enqueueQueueRoster(QueueRosterControlCommand<? extends QueueRosterCommand> command) {
        queueRosterCommands.add(Objects.requireNonNull(command, "command"));
    }

    Optional<QueueRosterControlCommand<? extends QueueRosterCommand>> pollQueueRoster() {
        return Optional.ofNullable(queueRosterCommands.poll());
    }

    QueueRosterControlRecord loadQueueRosterRecord(QueuePartitionKey partitionKey, long fencingEpoch) {
        return queueRosterRecords.computeIfAbsent(
                Objects.requireNonNull(partitionKey, "partitionKey"),
                ignored -> QueueRosterControlRecord.empty(fencingEpoch));
    }

    void storeQueueRosterRecord(QueuePartitionKey partitionKey, QueueRosterControlRecord record) {
        queueRosterRecords.put(
                Objects.requireNonNull(partitionKey, "partitionKey"),
                Objects.requireNonNull(record, "record"));
    }

    Optional<QueueRosterControlRecord> storedQueueRosterRecord(QueuePartitionKey partitionKey) {
        return Optional.ofNullable(queueRosterRecords.get(Objects.requireNonNull(partitionKey, "partitionKey")));
    }

    void recordQueueRosterDecision(QueueRosterDecision decision) {
        queueRosterDecisions.add(Objects.requireNonNull(decision, "decision"));
    }

    List<QueueRosterDecision> queueRosterDecisions() {
        return List.copyOf(queueRosterDecisions);
    }

    void publishQueueRosterEmissions(List<QueueRosterControlEmission> emissions) {
        queueRosterEmissions.addAll(List.copyOf(Objects.requireNonNull(emissions, "emissions")));
    }

    List<QueueRosterControlEmission> queueRosterEmissions() {
        return List.copyOf(queueRosterEmissions);
    }

    void enqueueFault(FaultControlCommand<? extends FaultCommand> command) {
        faultCommands.add(Objects.requireNonNull(command, "command"));
    }

    Optional<FaultControlCommand<? extends FaultCommand>> pollFault() {
        return Optional.ofNullable(faultCommands.poll());
    }

    FaultControlRecord loadFaultRecord(FaultId faultId, long fencingEpoch) {
        return faultRecords.computeIfAbsent(
                Objects.requireNonNull(faultId, "faultId"),
                ignored -> FaultControlRecord.empty(fencingEpoch));
    }

    void storeFaultRecord(FaultId faultId, FaultControlRecord record) {
        faultRecords.put(
                Objects.requireNonNull(faultId, "faultId"),
                Objects.requireNonNull(record, "record"));
    }

    Optional<FaultControlRecord> storedFaultRecord(FaultId faultId) {
        return Optional.ofNullable(faultRecords.get(Objects.requireNonNull(faultId, "faultId")));
    }

    void recordFaultDecision(FaultDecision decision) {
        faultDecisions.add(Objects.requireNonNull(decision, "decision"));
    }

    List<FaultDecision> faultDecisions() {
        return List.copyOf(faultDecisions);
    }

    void publishFaultEmissions(List<FaultControlEmission> emissions) {
        faultEmissions.addAll(List.copyOf(Objects.requireNonNull(emissions, "emissions")));
    }

    List<FaultControlEmission> faultEmissions() {
        return List.copyOf(faultEmissions);
    }

    void enqueueSharedShardAllocation(SharedShardAllocationRequest request) {
        sharedShardAllocationRequests.add(Objects.requireNonNull(request, "request"));
    }

    Optional<SharedShardAllocationRequest> pollSharedShardAllocation() {
        return Optional.ofNullable(sharedShardAllocationRequests.poll());
    }

    void recordSharedShardAllocationDecision(SharedShardAllocationDecision decision) {
        sharedShardAllocationDecisions.add(Objects.requireNonNull(decision, "decision"));
    }

    List<SharedShardAllocationDecision> sharedShardAllocationDecisions() {
        return List.copyOf(sharedShardAllocationDecisions);
    }

    void publishSharedShardAllocationEmissions(List<SharedShardAllocationEmission> emissions) {
        sharedShardAllocationEmissions.addAll(List.copyOf(Objects.requireNonNull(emissions, "emissions")));
    }

    List<SharedShardAllocationEmission> sharedShardAllocationEmissions() {
        return List.copyOf(sharedShardAllocationEmissions);
    }

    void enqueueSharedShardPlacement(
            SharedShardPlacementRequest request,
            List<SharedShardPlacementCandidate> candidates) {
        sharedShardPlacementRequests.add(new SharedShardPlacementWork(request, candidates));
    }

    Optional<SharedShardPlacementWork> pollSharedShardPlacement() {
        return Optional.ofNullable(sharedShardPlacementRequests.poll());
    }

    void recordSharedShardPlacementDecision(SharedShardPlacementDecision decision) {
        sharedShardPlacementDecisions.add(Objects.requireNonNull(decision, "decision"));
    }

    List<SharedShardPlacementDecision> sharedShardPlacementDecisions() {
        return List.copyOf(sharedShardPlacementDecisions);
    }

    void commit(String controllerDomain, ControllerRuntimeReceipt receipt) {
        String domain = requireDomain(controllerDomain);
        committedReceipts.computeIfAbsent(domain, ignored -> new ConcurrentLinkedQueue<>())
                .add(Objects.requireNonNull(receipt, "receipt"));
    }

    List<ControllerRuntimeReceipt> committedReceipts(String controllerDomain) {
        Queue<ControllerRuntimeReceipt> receipts = committedReceipts.get(requireDomain(controllerDomain));
        return receipts == null ? List.of() : List.copyOf(receipts);
    }

    private static String requireDomain(String value) {
        String checked = Objects.requireNonNull(value, "controllerDomain").trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException("controllerDomain must not be blank");
        }
        return checked;
    }
}

record SharedShardPlacementWork(
        SharedShardPlacementRequest request,
        List<SharedShardPlacementCandidate> candidates) {
    SharedShardPlacementWork {
        request = Objects.requireNonNull(request, "request");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
    }
}
