package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.control.allocation.SharedShardAllocationBridge;
import sh.harold.fulcrum.control.allocation.SharedShardAllocationDecision;
import sh.harold.fulcrum.control.allocation.SharedShardAllocationRequest;
import sh.harold.fulcrum.control.capability.CapabilityEnablementCommand;
import sh.harold.fulcrum.control.capability.CapabilityEnablementControlCommand;
import sh.harold.fulcrum.control.capability.CapabilityEnablementControlRecord;
import sh.harold.fulcrum.control.capability.CapabilityEnablementController;
import sh.harold.fulcrum.control.capability.CapabilityEnablementDecision;
import sh.harold.fulcrum.control.fault.FaultCommand;
import sh.harold.fulcrum.control.fault.FaultControlCommand;
import sh.harold.fulcrum.control.fault.FaultControlRecord;
import sh.harold.fulcrum.control.fault.FaultController;
import sh.harold.fulcrum.control.fault.FaultDecision;
import sh.harold.fulcrum.control.instance.InstanceRegistryCommand;
import sh.harold.fulcrum.control.instance.InstanceRegistryControlCommand;
import sh.harold.fulcrum.control.instance.InstanceRegistryController;
import sh.harold.fulcrum.control.instance.InstanceRegistryDecision;
import sh.harold.fulcrum.control.instance.InstanceRegistryRecord;
import sh.harold.fulcrum.control.instance.SharedShardPlacementController;
import sh.harold.fulcrum.control.instance.SharedShardPlacementDecision;
import sh.harold.fulcrum.control.lifecycle.ExperienceSessionCommand;
import sh.harold.fulcrum.control.lifecycle.ExperienceSessionControlCommand;
import sh.harold.fulcrum.control.lifecycle.ExperienceSessionControlRecord;
import sh.harold.fulcrum.control.lifecycle.ExperienceSessionController;
import sh.harold.fulcrum.control.lifecycle.ExperienceSessionDecision;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceCommand;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceControlCommand;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceControlRecord;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceController;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceDecision;
import sh.harold.fulcrum.control.queue.QueueRosterCommand;
import sh.harold.fulcrum.control.queue.QueueRosterControlCommand;
import sh.harold.fulcrum.control.queue.QueueRosterControlRecord;
import sh.harold.fulcrum.control.queue.QueueRosterController;
import sh.harold.fulcrum.control.queue.QueueRosterDecision;
import sh.harold.fulcrum.control.route.RouteAttemptCommand;
import sh.harold.fulcrum.control.route.RouteAttemptControlCommand;
import sh.harold.fulcrum.control.route.RouteAttemptControlRecord;
import sh.harold.fulcrum.control.route.RouteAttemptController;
import sh.harold.fulcrum.control.route.RouteAttemptDecision;
import sh.harold.fulcrum.host.api.HostAllocationPort;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class ControllerWorkerCatalog {
    static final String INSTANCE_REGISTRY = "instance-registry";
    static final String ROUTE_ATTEMPT = "route-attempt";
    static final String EXPERIENCE_SESSION = "experience-session";
    static final String LIFECYCLE_TRACE = "lifecycle-trace";
    static final String CAPABILITY_ENABLEMENT = "capability-enablement";
    static final String QUEUE_ROSTER = "queue-roster";
    static final String FAULT = "fault";
    static final String SHARED_SHARD_PLACEMENT = "shared-shard-placement";
    static final String SHARED_SHARD_ALLOCATION = "shared-shard-allocation";

    private final LocalControllerRuntimeBindings bindings;
    private final SharedShardAllocationBridge sharedShardAllocationBridge;
    private final InstanceRegistryController instanceRegistryController = new InstanceRegistryController();
    private final RouteAttemptController routeAttemptController = new RouteAttemptController();
    private final ExperienceSessionController experienceSessionController = new ExperienceSessionController();
    private final LifecycleTraceController lifecycleTraceController = new LifecycleTraceController();
    private final CapabilityEnablementController capabilityEnablementController = new CapabilityEnablementController();
    private final QueueRosterController queueRosterController = new QueueRosterController();
    private final FaultController faultController = new FaultController();
    private final SharedShardPlacementController sharedShardPlacementController = new SharedShardPlacementController();
    private final long fencingEpoch;

    ControllerWorkerCatalog(LocalControllerRuntimeBindings bindings, long fencingEpoch) {
        this(bindings, unconfiguredAllocationPort(), fencingEpoch);
    }

    ControllerWorkerCatalog(
            LocalControllerRuntimeBindings bindings,
            HostAllocationPort allocationPort,
            long fencingEpoch) {
        this(bindings, new SharedShardAllocationBridge(allocationPort), fencingEpoch);
    }

    private ControllerWorkerCatalog(
            LocalControllerRuntimeBindings bindings,
            SharedShardAllocationBridge sharedShardAllocationBridge,
            long fencingEpoch) {
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.sharedShardAllocationBridge =
                Objects.requireNonNull(sharedShardAllocationBridge, "sharedShardAllocationBridge");
        if (fencingEpoch < 0) {
            throw new IllegalArgumentException("fencingEpoch must be non-negative");
        }
        this.fencingEpoch = fencingEpoch;
    }

    static List<String> controllerDomains() {
        return List.of(
                INSTANCE_REGISTRY,
                ROUTE_ATTEMPT,
                EXPERIENCE_SESSION,
                LIFECYCLE_TRACE,
                CAPABILITY_ENABLEMENT,
                QUEUE_ROSTER,
                FAULT,
                SHARED_SHARD_PLACEMENT,
                SHARED_SHARD_ALLOCATION);
    }

    List<ControllerWorkerBinding> workerBindings() {
        return List.of(
                new ControllerWorkerBinding(INSTANCE_REGISTRY, this::handleInstanceRegistryNext),
                new ControllerWorkerBinding(ROUTE_ATTEMPT, this::handleRouteAttemptNext),
                new ControllerWorkerBinding(EXPERIENCE_SESSION, this::handleExperienceSessionNext),
                new ControllerWorkerBinding(LIFECYCLE_TRACE, this::handleLifecycleTraceNext),
                new ControllerWorkerBinding(CAPABILITY_ENABLEMENT, this::handleCapabilityEnablementNext),
                new ControllerWorkerBinding(QUEUE_ROSTER, this::handleQueueRosterNext),
                new ControllerWorkerBinding(FAULT, this::handleFaultNext),
                new ControllerWorkerBinding(SHARED_SHARD_PLACEMENT, this::handleSharedShardPlacementNext),
                new ControllerWorkerBinding(SHARED_SHARD_ALLOCATION, this::handleSharedShardAllocationNext));
    }

    private Optional<ControllerRuntimeReceipt> handleInstanceRegistryNext() {
        return bindings.pollInstanceRegistry().map(this::handleInstanceRegistryCommand);
    }

    private ControllerRuntimeReceipt handleInstanceRegistryCommand(
            InstanceRegistryControlCommand<? extends InstanceRegistryCommand> command) {
        InstanceRegistryRecord currentRecord = bindings.loadInstanceRegistryRecord(
                command.envelope().payload().instanceId(),
                fencingEpoch);
        InstanceRegistryDecision decision = instanceRegistryController.handle(command, currentRecord);
        bindings.storeInstanceRegistryRecord(command.envelope().payload().instanceId(), decision.record());
        bindings.recordInstanceRegistryDecision(decision);
        bindings.publishInstanceRegistryEmissions(decision.emissions());
        return commit(INSTANCE_REGISTRY, command.envelope().commandId().value());
    }

    private Optional<ControllerRuntimeReceipt> handleRouteAttemptNext() {
        return bindings.pollRouteAttempt().map(this::handleRouteAttemptCommand);
    }

    private ControllerRuntimeReceipt handleRouteAttemptCommand(
            RouteAttemptControlCommand<? extends RouteAttemptCommand> command) {
        RouteAttemptControlRecord currentRecord = bindings.loadRouteAttemptRecord(
                command.envelope().payload().routeAttemptId(),
                fencingEpoch);
        RouteAttemptDecision decision = routeAttemptController.handle(command, currentRecord);
        bindings.storeRouteAttemptRecord(command.envelope().payload().routeAttemptId(), decision.record());
        bindings.recordRouteAttemptDecision(decision);
        bindings.publishRouteAttemptEmissions(decision.emissions());
        return commit(ROUTE_ATTEMPT, command.envelope().commandId().value());
    }

    private Optional<ControllerRuntimeReceipt> handleExperienceSessionNext() {
        return bindings.pollExperienceSession().map(this::handleExperienceSessionCommand);
    }

    private ControllerRuntimeReceipt handleExperienceSessionCommand(
            ExperienceSessionControlCommand<? extends ExperienceSessionCommand> command) {
        ExperienceSessionControlRecord currentRecord = bindings.loadExperienceSessionRecord(
                command.envelope().payload().sessionId(),
                fencingEpoch);
        ExperienceSessionDecision decision = experienceSessionController.handle(command, currentRecord);
        bindings.storeExperienceSessionRecord(command.envelope().payload().sessionId(), decision.record());
        bindings.recordExperienceSessionDecision(decision);
        bindings.publishExperienceSessionEmissions(decision.emissions());
        return commit(EXPERIENCE_SESSION, command.envelope().commandId().value());
    }

    private Optional<ControllerRuntimeReceipt> handleLifecycleTraceNext() {
        return bindings.pollLifecycleTrace().map(this::handleLifecycleTraceCommand);
    }

    private ControllerRuntimeReceipt handleLifecycleTraceCommand(
            LifecycleTraceControlCommand<? extends LifecycleTraceCommand> command) {
        LifecycleTraceControlRecord currentRecord = bindings.loadLifecycleTraceRecord(
                command.envelope().payload().traceId(),
                fencingEpoch);
        LifecycleTraceDecision decision = lifecycleTraceController.handle(command, currentRecord);
        bindings.storeLifecycleTraceRecord(command.envelope().payload().traceId(), decision.record());
        bindings.recordLifecycleTraceDecision(decision);
        bindings.publishLifecycleTraceEmissions(decision.emissions());
        return commit(LIFECYCLE_TRACE, command.envelope().commandId().value());
    }

    private Optional<ControllerRuntimeReceipt> handleCapabilityEnablementNext() {
        return bindings.pollCapabilityEnablement().map(this::handleCapabilityEnablementCommand);
    }

    private ControllerRuntimeReceipt handleCapabilityEnablementCommand(
            CapabilityEnablementControlCommand<? extends CapabilityEnablementCommand> command) {
        CapabilityEnablementControlRecord currentRecord = bindings.loadCapabilityEnablementRecord(
                command.envelope().payload().scope(),
                fencingEpoch);
        CapabilityEnablementDecision decision = capabilityEnablementController.handle(command, currentRecord);
        bindings.storeCapabilityEnablementRecord(command.envelope().payload().scope(), decision.record());
        bindings.recordCapabilityEnablementDecision(decision);
        bindings.publishCapabilityEnablementEmissions(decision.emissions());
        return commit(CAPABILITY_ENABLEMENT, command.envelope().commandId().value());
    }

    private Optional<ControllerRuntimeReceipt> handleQueueRosterNext() {
        return bindings.pollQueueRoster().map(this::handleQueueRosterCommand);
    }

    private ControllerRuntimeReceipt handleQueueRosterCommand(
            QueueRosterControlCommand<? extends QueueRosterCommand> command) {
        QueueRosterControlRecord currentRecord = bindings.loadQueueRosterRecord(
                command.envelope().payload().partitionKey(),
                fencingEpoch);
        QueueRosterDecision decision = queueRosterController.handle(command, currentRecord);
        bindings.storeQueueRosterRecord(command.envelope().payload().partitionKey(), decision.record());
        bindings.recordQueueRosterDecision(decision);
        bindings.publishQueueRosterEmissions(decision.emissions());
        return commit(QUEUE_ROSTER, command.envelope().commandId().value());
    }

    private Optional<ControllerRuntimeReceipt> handleFaultNext() {
        return bindings.pollFault().map(this::handleFaultCommand);
    }

    private ControllerRuntimeReceipt handleFaultCommand(FaultControlCommand<? extends FaultCommand> command) {
        FaultControlRecord currentRecord = bindings.loadFaultRecord(
                command.envelope().payload().faultId(),
                fencingEpoch);
        FaultDecision decision = faultController.handle(command, currentRecord);
        bindings.storeFaultRecord(command.envelope().payload().faultId(), decision.record());
        bindings.recordFaultDecision(decision);
        bindings.publishFaultEmissions(decision.emissions());
        return commit(FAULT, command.envelope().commandId().value());
    }

    private Optional<ControllerRuntimeReceipt> handleSharedShardPlacementNext() {
        return bindings.pollSharedShardPlacement().map(this::handleSharedShardPlacementRequest);
    }

    private ControllerRuntimeReceipt handleSharedShardPlacementRequest(SharedShardPlacementWork work) {
        SharedShardPlacementDecision decision = sharedShardPlacementController.place(work.request(), work.candidates());
        bindings.recordSharedShardPlacementDecision(decision);
        return commit(SHARED_SHARD_PLACEMENT, work.request().placementAttemptId());
    }

    private Optional<ControllerRuntimeReceipt> handleSharedShardAllocationNext() {
        return bindings.pollSharedShardAllocation().map(this::handleSharedShardAllocationRequest);
    }

    private ControllerRuntimeReceipt handleSharedShardAllocationRequest(SharedShardAllocationRequest request) {
        SharedShardAllocationDecision decision = sharedShardAllocationBridge.allocate(request);
        bindings.recordSharedShardAllocationDecision(decision);
        bindings.publishSharedShardAllocationEmissions(decision.emissions());
        return commit(SHARED_SHARD_ALLOCATION, request.sessionId().value());
    }

    private ControllerRuntimeReceipt commit(String controllerDomain, String commandId) {
        ControllerRuntimeReceipt receipt = new ControllerRuntimeReceipt(controllerDomain, commandId);
        bindings.commit(controllerDomain, receipt);
        return receipt;
    }

    private static HostAllocationPort unconfiguredAllocationPort() {
        return request -> {
            throw new IllegalStateException("Agones allocation port is not configured");
        };
    }
}
