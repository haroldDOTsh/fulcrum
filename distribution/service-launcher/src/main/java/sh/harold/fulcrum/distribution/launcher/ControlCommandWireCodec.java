package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.PresenceId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.control.allocation.SharedShardAllocationRequest;
import sh.harold.fulcrum.control.capability.CapabilityEnablementControlCommand;
import sh.harold.fulcrum.control.capability.ControlCapabilityNames;
import sh.harold.fulcrum.control.capability.EnableCapability;
import sh.harold.fulcrum.control.fault.ControlFaultNames;
import sh.harold.fulcrum.control.fault.FaultControlCommand;
import sh.harold.fulcrum.control.fault.FaultId;
import sh.harold.fulcrum.control.fault.FaultTargetType;
import sh.harold.fulcrum.control.fault.RecordFault;
import sh.harold.fulcrum.control.lifecycle.ControlLifecycleNames;
import sh.harold.fulcrum.control.lifecycle.ExperienceSessionControlCommand;
import sh.harold.fulcrum.control.lifecycle.LifecyclePhase;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceControlCommand;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceId;
import sh.harold.fulcrum.control.lifecycle.RecordLifecycleObservation;
import sh.harold.fulcrum.control.lifecycle.RequestExperienceSession;
import sh.harold.fulcrum.control.queue.ControlQueueNames;
import sh.harold.fulcrum.control.queue.QueueIntentId;
import sh.harold.fulcrum.control.queue.QueueRosterControlCommand;
import sh.harold.fulcrum.control.queue.SubmitQueueIntent;
import sh.harold.fulcrum.control.instance.ExperienceShape;
import sh.harold.fulcrum.control.instance.InstanceRegistryStatus;
import sh.harold.fulcrum.control.instance.InstanceSnapshot;
import sh.harold.fulcrum.control.instance.SharedShardExperienceDescriptor;
import sh.harold.fulcrum.control.instance.SharedShardOccupancySnapshot;
import sh.harold.fulcrum.control.instance.SharedShardPlacementCandidate;
import sh.harold.fulcrum.control.instance.SharedShardPlacementRequest;
import sh.harold.fulcrum.control.instance.SharedShardPoolDescriptor;
import sh.harold.fulcrum.control.route.AcknowledgeRouteAttempt;
import sh.harold.fulcrum.control.route.ControlRouteNames;
import sh.harold.fulcrum.control.route.IssueProxyRoute;
import sh.harold.fulcrum.control.route.ObserveHostAttach;
import sh.harold.fulcrum.control.route.PrepareHostRoute;
import sh.harold.fulcrum.control.route.RequestRouteAttempt;
import sh.harold.fulcrum.control.route.RouteAttemptCommand;
import sh.harold.fulcrum.control.route.RouteAttemptControlCommand;
import sh.harold.fulcrum.control.route.RouteAttemptId;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class ControlCommandWireCodec {
    private ControlCommandWireCodec() {
    }

    static RouteAttemptControlCommand<RequestRouteAttempt> decodeRouteAttemptRequest(
            ConsumerRecord<String, String> record) {
        RouteAttemptControlCommand<? extends RouteAttemptCommand> command = decodeRouteAttemptCommand(record);
        if (command.envelope().payload() instanceof RequestRouteAttempt request) {
            return typedRouteCommand(command, request);
        }
        throw new IllegalArgumentException("Expected route-attempt request command");
    }

    static RouteAttemptControlCommand<? extends RouteAttemptCommand> decodeRouteAttemptCommand(
            ConsumerRecord<String, String> record) {
        Map<String, String> fields = fields(record.value());
        TraceEnvelope trace = decodeTrace(fields);
        RouteAttemptCommand payload = routeAttemptPayload(fields, trace);
        return new RouteAttemptControlCommand<>(
                envelope(fields, record, ControlRouteNames.CONTRACT, new CommandName(required(fields, "commandName")), trace, payload),
                authenticatedPrincipal(fields),
                longValue(fields, "fencingEpoch"),
                optionalRevision(fields, "expectedRevision"),
                required(fields, "payloadFingerprint"),
                instant(fields, "receivedAt"));
    }

    static String encodeRouteAttemptRequest(RouteAttemptControlCommand<RequestRouteAttempt> command) {
        return encodeRouteAttemptCommand(command);
    }

    static String encodeRouteAttemptCommand(RouteAttemptControlCommand<? extends RouteAttemptCommand> command) {
        Map<String, String> fields = commandFields(command.envelope(), command.authenticatedPrincipal(),
                command.fencingEpoch(), command.expectedRevision(), command.payloadFingerprint(), command.receivedAt());
        encodeRouteAttemptPayload(fields, command.envelope().payload());
        return lines(fields);
    }

    @SuppressWarnings("unchecked")
    private static RouteAttemptControlCommand<RequestRouteAttempt> typedRouteCommand(
            RouteAttemptControlCommand<? extends RouteAttemptCommand> command,
            RequestRouteAttempt payload) {
        return (RouteAttemptControlCommand<RequestRouteAttempt>) new RouteAttemptControlCommand<>(
                new CommandEnvelope<>(
                        command.envelope().commandId(),
                        command.envelope().idempotencyKey(),
                        command.envelope().principalId(),
                        command.envelope().aggregateId(),
                        command.envelope().contractName(),
                        command.envelope().commandName(),
                        command.envelope().traceEnvelope(),
                        command.envelope().deadlineAt(),
                        payload),
                command.authenticatedPrincipal(),
                command.fencingEpoch(),
                command.expectedRevision(),
                command.payloadFingerprint(),
                command.receivedAt());
    }

    private static RouteAttemptCommand routeAttemptPayload(Map<String, String> fields, TraceEnvelope trace) {
        String commandName = required(fields, "commandName");
        RouteAttemptId routeAttemptId = new RouteAttemptId(required(fields, "routeAttemptId"));
        if (ControlRouteNames.REQUEST_ROUTE_ATTEMPT.value().equals(commandName)) {
            return new RequestRouteAttempt(
                    routeAttemptId,
                    new RouteId(required(fields, "routeId")),
                    new SessionId(required(fields, "sessionId")),
                    new SlotId(required(fields, "allocationSlotId")),
                    subjectIds(fields, "subjectIds"),
                    instanceIds(fields, "proxyInstanceIds"),
                    new PresenceId(required(fields, "sourcePresenceId")),
                    new InstanceId(required(fields, "targetInstanceId")),
                    new ResolvedManifestId(required(fields, "targetResolvedManifestId")),
                    instant(fields, "requestedAt"),
                    instant(fields, "payloadDeadlineAt"),
                    trace);
        }
        if (ControlRouteNames.ISSUE_PROXY_ROUTE.value().equals(commandName)) {
            return new IssueProxyRoute(routeAttemptId, instant(fields, "issuedAt"));
        }
        if (ControlRouteNames.PREPARE_HOST_ROUTE.value().equals(commandName)) {
            return new PrepareHostRoute(routeAttemptId, instant(fields, "issuedAt"));
        }
        if (ControlRouteNames.OBSERVE_HOST_ATTACH.value().equals(commandName)) {
            return new ObserveHostAttach(routeAttemptId, instant(fields, "observedAt"));
        }
        if (ControlRouteNames.ACKNOWLEDGE_ROUTE_ATTEMPT.value().equals(commandName)) {
            return new AcknowledgeRouteAttempt(routeAttemptId, instant(fields, "acknowledgedAt"));
        }
        throw new IllegalArgumentException("Unsupported control command " + commandName);
    }

    private static void encodeRouteAttemptPayload(Map<String, String> fields, RouteAttemptCommand payload) {
        if (payload instanceof RequestRouteAttempt request) {
            fields.put("routeAttemptId", request.routeAttemptId().value());
            fields.put("routeId", request.routeId().value());
            fields.put("sessionId", request.sessionId().value());
            fields.put("allocationSlotId", request.allocationSlotId().value());
            fields.put("subjectIds", joinSubjectIds(request.subjectIds()));
            fields.put("proxyInstanceIds", joinInstanceIds(request.proxyInstanceIds()));
            fields.put("sourcePresenceId", request.sourcePresenceId().value());
            fields.put("targetInstanceId", request.targetInstanceId().value());
            fields.put("targetResolvedManifestId", request.targetResolvedManifestId().value());
            fields.put("requestedAt", request.requestedAt().toString());
            fields.put("payloadDeadlineAt", request.deadlineAt().toString());
            return;
        }
        if (payload instanceof IssueProxyRoute issueProxy) {
            fields.put("routeAttemptId", issueProxy.routeAttemptId().value());
            fields.put("issuedAt", issueProxy.issuedAt().toString());
            return;
        }
        if (payload instanceof PrepareHostRoute prepareHost) {
            fields.put("routeAttemptId", prepareHost.routeAttemptId().value());
            fields.put("issuedAt", prepareHost.issuedAt().toString());
            return;
        }
        if (payload instanceof ObserveHostAttach observeHostAttach) {
            fields.put("routeAttemptId", observeHostAttach.routeAttemptId().value());
            fields.put("observedAt", observeHostAttach.observedAt().toString());
            return;
        }
        if (payload instanceof AcknowledgeRouteAttempt acknowledge) {
            fields.put("routeAttemptId", acknowledge.routeAttemptId().value());
            fields.put("acknowledgedAt", acknowledge.acknowledgedAt().toString());
            return;
        }
        throw new IllegalArgumentException("Unsupported route-attempt payload " + payload.getClass().getSimpleName());
    }

    static ExperienceSessionControlCommand<RequestExperienceSession> decodeExperienceSessionRequest(
            ConsumerRecord<String, String> record) {
        Map<String, String> fields = fields(record.value());
        requireCommand(fields, ControlLifecycleNames.REQUEST_EXPERIENCE_SESSION);
        TraceEnvelope trace = decodeTrace(fields);
        RequestExperienceSession payload = new RequestExperienceSession(
                new SessionId(required(fields, "sessionId")),
                new ExperienceId(required(fields, "experienceId")),
                optional(fields, "modeId"),
                required(fields, "sessionType"),
                subjectIds(fields, "subjectIds"),
                instant(fields, "requestedAt"),
                trace);
        return new ExperienceSessionControlCommand<>(
                envelope(fields, record, ControlLifecycleNames.SESSION_CONTRACT,
                        ControlLifecycleNames.REQUEST_EXPERIENCE_SESSION, trace, payload),
                authenticatedPrincipal(fields),
                longValue(fields, "fencingEpoch"),
                optionalRevision(fields, "expectedRevision"),
                required(fields, "payloadFingerprint"),
                instant(fields, "receivedAt"));
    }

    static String encodeExperienceSessionRequest(ExperienceSessionControlCommand<RequestExperienceSession> command) {
        Map<String, String> fields = commandFields(command.envelope(), command.authenticatedPrincipal(),
                command.fencingEpoch(), command.expectedRevision(), command.payloadFingerprint(), command.receivedAt());
        RequestExperienceSession payload = command.envelope().payload();
        fields.put("sessionId", payload.sessionId().value());
        fields.put("experienceId", payload.experienceId().value());
        fields.put("modeId", payload.modeId().orElse(""));
        fields.put("sessionType", payload.sessionType());
        fields.put("subjectIds", joinSubjectIds(payload.subjectIds()));
        fields.put("requestedAt", payload.requestedAt().toString());
        return lines(fields);
    }

    static LifecycleTraceControlCommand<RecordLifecycleObservation> decodeLifecycleTraceRecord(
            ConsumerRecord<String, String> record) {
        Map<String, String> fields = fields(record.value());
        requireCommand(fields, ControlLifecycleNames.RECORD_LIFECYCLE_OBSERVATION);
        TraceEnvelope trace = decodeTrace(fields);
        RecordLifecycleObservation payload = new RecordLifecycleObservation(
                new LifecycleTraceId(required(fields, "lifecycleTraceId")),
                LifecyclePhase.valueOf(required(fields, "phase")),
                required(fields, "observedAggregateType"),
                required(fields, "observedAggregateId"),
                optional(fields, "sessionId").map(SessionId::new),
                optional(fields, "resolvedManifestId").map(ResolvedManifestId::new),
                instant(fields, "observedAt"),
                trace);
        return new LifecycleTraceControlCommand<>(
                envelope(fields, record, ControlLifecycleNames.TRACE_CONTRACT,
                        ControlLifecycleNames.RECORD_LIFECYCLE_OBSERVATION, trace, payload),
                authenticatedPrincipal(fields),
                longValue(fields, "fencingEpoch"),
                optionalRevision(fields, "expectedRevision"),
                required(fields, "payloadFingerprint"),
                instant(fields, "receivedAt"));
    }

    static String encodeLifecycleTraceRecord(LifecycleTraceControlCommand<RecordLifecycleObservation> command) {
        Map<String, String> fields = commandFields(command.envelope(), command.authenticatedPrincipal(),
                command.fencingEpoch(), command.expectedRevision(), command.payloadFingerprint(), command.receivedAt());
        RecordLifecycleObservation payload = command.envelope().payload();
        fields.put("lifecycleTraceId", payload.traceId().value());
        fields.put("phase", payload.phase().name());
        fields.put("observedAggregateType", payload.aggregateType());
        fields.put("observedAggregateId", payload.aggregateId());
        fields.put("sessionId", payload.sessionId().map(SessionId::value).orElse(""));
        fields.put("resolvedManifestId", payload.resolvedManifestId().map(ResolvedManifestId::value).orElse(""));
        fields.put("observedAt", payload.observedAt().toString());
        return lines(fields);
    }

    static CapabilityEnablementControlCommand<EnableCapability> decodeCapabilityEnablement(
            ConsumerRecord<String, String> record) {
        Map<String, String> fields = fields(record.value());
        requireCommand(fields, ControlCapabilityNames.ENABLE);
        TraceEnvelope trace = decodeTrace(fields);
        EnableCapability payload = new EnableCapability(
                new CapabilityScope(required(fields, "scope")),
                new CapabilityId(required(fields, "capabilityId")),
                required(fields, "contractSet"),
                required(fields, "reason"),
                instant(fields, "enabledAt"),
                trace);
        return new CapabilityEnablementControlCommand<>(
                envelope(fields, record, ControlCapabilityNames.CONTRACT, ControlCapabilityNames.ENABLE, trace, payload),
                authenticatedPrincipal(fields),
                longValue(fields, "fencingEpoch"),
                optionalRevision(fields, "expectedRevision"),
                required(fields, "payloadFingerprint"),
                instant(fields, "receivedAt"));
    }

    static String encodeCapabilityEnablement(CapabilityEnablementControlCommand<EnableCapability> command) {
        Map<String, String> fields = commandFields(command.envelope(), command.authenticatedPrincipal(),
                command.fencingEpoch(), command.expectedRevision(), command.payloadFingerprint(), command.receivedAt());
        EnableCapability payload = command.envelope().payload();
        fields.put("scope", payload.scope().value());
        fields.put("capabilityId", payload.capabilityId().value());
        fields.put("contractSet", payload.contractSet());
        fields.put("reason", payload.reason());
        fields.put("enabledAt", payload.enabledAt().toString());
        return lines(fields);
    }

    static QueueRosterControlCommand<SubmitQueueIntent> decodeQueueRosterSubmit(
            ConsumerRecord<String, String> record) {
        Map<String, String> fields = fields(record.value());
        requireCommand(fields, ControlQueueNames.SUBMIT_QUEUE_INTENT);
        TraceEnvelope trace = decodeTrace(fields);
        SubmitQueueIntent payload = new SubmitQueueIntent(
                new QueueIntentId(required(fields, "queueIntentId")),
                subjectIds(fields, "subjectIds"),
                new ExperienceId(required(fields, "experienceId")),
                optional(fields, "modeId"),
                new PoolId(required(fields, "poolId")),
                intValue(fields, "priority"),
                instant(fields, "createdAt"),
                instant(fields, "queueDeadlineAt"),
                trace);
        return new QueueRosterControlCommand<>(
                envelope(fields, record, ControlQueueNames.CONTRACT, ControlQueueNames.SUBMIT_QUEUE_INTENT, trace, payload),
                authenticatedPrincipal(fields),
                longValue(fields, "fencingEpoch"),
                optionalRevision(fields, "expectedRevision"),
                required(fields, "payloadFingerprint"),
                instant(fields, "receivedAt"));
    }

    static String encodeQueueRosterSubmit(QueueRosterControlCommand<SubmitQueueIntent> command) {
        Map<String, String> fields = commandFields(command.envelope(), command.authenticatedPrincipal(),
                command.fencingEpoch(), command.expectedRevision(), command.payloadFingerprint(), command.receivedAt());
        SubmitQueueIntent payload = command.envelope().payload();
        fields.put("queueIntentId", payload.queueIntentId().value());
        fields.put("subjectIds", joinSubjectIds(payload.subjectIds()));
        fields.put("experienceId", payload.experienceId().value());
        fields.put("modeId", payload.modeId().orElse(""));
        fields.put("poolId", payload.poolId().value());
        fields.put("priority", Integer.toString(payload.priority()));
        fields.put("createdAt", payload.createdAt().toString());
        fields.put("queueDeadlineAt", payload.deadlineAt().toString());
        return lines(fields);
    }

    static FaultControlCommand<RecordFault> decodeFaultRecord(ConsumerRecord<String, String> record) {
        Map<String, String> fields = fields(record.value());
        requireCommand(fields, ControlFaultNames.RECORD_FAULT);
        TraceEnvelope trace = decodeTrace(fields);
        RecordFault payload = new RecordFault(
                new FaultId(required(fields, "faultId")),
                FaultTargetType.valueOf(required(fields, "targetType")),
                required(fields, "targetId"),
                required(fields, "scope"),
                required(fields, "reason"),
                intValue(fields, "quarantineAfterCount"),
                instant(fields, "observedAt"),
                trace);
        return new FaultControlCommand<>(
                envelope(fields, record, ControlFaultNames.CONTRACT, ControlFaultNames.RECORD_FAULT, trace, payload),
                authenticatedPrincipal(fields),
                longValue(fields, "fencingEpoch"),
                optionalRevision(fields, "expectedRevision"),
                required(fields, "payloadFingerprint"),
                instant(fields, "receivedAt"));
    }

    static String encodeFaultRecord(FaultControlCommand<RecordFault> command) {
        Map<String, String> fields = commandFields(command.envelope(), command.authenticatedPrincipal(),
                command.fencingEpoch(), command.expectedRevision(), command.payloadFingerprint(), command.receivedAt());
        RecordFault payload = command.envelope().payload();
        fields.put("faultId", payload.faultId().value());
        fields.put("targetType", payload.targetType().name());
        fields.put("targetId", payload.targetId());
        fields.put("scope", payload.scope());
        fields.put("reason", payload.reason());
        fields.put("quarantineAfterCount", Integer.toString(payload.quarantineAfterCount()));
        fields.put("observedAt", payload.observedAt().toString());
        return lines(fields);
    }

    static SharedShardPlacementWireRequest decodeSharedShardPlacementRequest(ConsumerRecord<String, String> record) {
        Map<String, String> fields = fields(record.value());
        TraceEnvelope trace = decodeTrace(fields);
        SharedShardExperienceDescriptor experience = new SharedShardExperienceDescriptor(
                new ExperienceId(required(fields, "experienceId")),
                ExperienceShape.SHARED_SHARD,
                new SharedShardPoolDescriptor(
                        new PoolId(required(fields, "poolId")),
                        required(fields, "agonesFleetName"),
                        intValue(fields, "targetCapacity"),
                        intValue(fields, "hardCapacity")),
                new ResolvedManifestId(required(fields, "resolvedManifestId")));
        SharedShardPlacementRequest request = new SharedShardPlacementRequest(
                experience,
                new SubjectId(UUID.fromString(required(fields, "subjectId"))),
                new PresenceId(required(fields, "presenceId")),
                optional(fields, "placementAttemptId").orElse(record.key()),
                optional(fields, "capabilityScopeFingerprint"),
                instant(fields, "requestedAt"),
                trace);
        int candidateCount = intValue(fields, "candidateCount");
        java.util.ArrayList<SharedShardPlacementCandidate> candidates = new java.util.ArrayList<>();
        for (int index = 0; index < candidateCount; index++) {
            String prefix = "candidate." + index + ".";
            InstanceSnapshot snapshot = new InstanceSnapshot(
                    new InstanceId(required(fields, prefix + "instanceId")),
                    required(fields, prefix + "instanceKind"),
                    new PoolId(required(fields, prefix + "poolId")),
                    new MachineRef(required(fields, prefix + "machineRef")),
                    new PrincipalId(required(fields, prefix + "principalId")),
                    optional(fields, prefix + "resolvedManifestId").map(ResolvedManifestId::new),
                    InstanceRegistryStatus.valueOf(required(fields, prefix + "status")),
                    optional(fields, prefix + "statusReason"),
                    trace,
                    instant(fields, prefix + "updatedAt"));
            SharedShardOccupancySnapshot occupancy = new SharedShardOccupancySnapshot(
                    new SessionId(required(fields, prefix + "sessionId")),
                    new SlotId(required(fields, prefix + "slotId")),
                    intValue(fields, prefix + "currentPresences"),
                    intValue(fields, prefix + "hardCapacity"),
                    booleanValue(fields, prefix + "acceptingPresences"),
                    instant(fields, prefix + "observedAt"),
                    trace);
            candidates.add(new SharedShardPlacementCandidate(snapshot, occupancy));
        }
        return new SharedShardPlacementWireRequest(request, candidates);
    }

    static String encodeSharedShardPlacementRequest(
            SharedShardPlacementRequest request,
            List<SharedShardPlacementCandidate> candidates) {
        Objects.requireNonNull(request, "request");
        List<SharedShardPlacementCandidate> checkedCandidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("placementAttemptId", request.placementAttemptId());
        fields.put("experienceId", request.experience().experienceId().value());
        fields.put("poolId", request.experience().poolId().value());
        fields.put("agonesFleetName", request.experience().poolDescriptor().agonesFleetName());
        fields.put("targetCapacity", Integer.toString(request.experience().poolDescriptor().targetCapacity()));
        fields.put("hardCapacity", Integer.toString(request.experience().hardCapacity()));
        fields.put("resolvedManifestId", request.experience().resolvedManifestId().value());
        fields.put("subjectId", request.subjectId().value().toString());
        fields.put("presenceId", request.presenceId().value());
        fields.put("capabilityScopeFingerprint", request.capabilityScopeFingerprint().orElse(""));
        fields.put("requestedAt", request.requestedAt().toString());
        encodeTrace(fields, request.traceEnvelope());
        fields.put("candidateCount", Integer.toString(checkedCandidates.size()));
        for (int index = 0; index < checkedCandidates.size(); index++) {
            SharedShardPlacementCandidate candidate = checkedCandidates.get(index);
            InstanceSnapshot snapshot = candidate.instanceSnapshot();
            SharedShardOccupancySnapshot occupancy = candidate.occupancySnapshot();
            String prefix = "candidate." + index + ".";
            fields.put(prefix + "instanceId", snapshot.instanceId().value());
            fields.put(prefix + "instanceKind", snapshot.instanceKind());
            fields.put(prefix + "poolId", snapshot.poolId().value());
            fields.put(prefix + "machineRef", snapshot.machineRef().value());
            fields.put(prefix + "principalId", snapshot.instancePrincipalId().value());
            fields.put(prefix + "resolvedManifestId", snapshot.resolvedManifestId().map(ResolvedManifestId::value).orElse(""));
            fields.put(prefix + "status", snapshot.status().name());
            fields.put(prefix + "statusReason", snapshot.statusReason().orElse(""));
            fields.put(prefix + "updatedAt", snapshot.updatedAt().toString());
            fields.put(prefix + "sessionId", occupancy.sessionId().value());
            fields.put(prefix + "slotId", occupancy.slotId().value());
            fields.put(prefix + "currentPresences", Integer.toString(occupancy.currentPresences()));
            fields.put(prefix + "hardCapacity", Integer.toString(occupancy.hardCapacity()));
            fields.put(prefix + "acceptingPresences", Boolean.toString(occupancy.acceptingPresences()));
            fields.put(prefix + "observedAt", occupancy.observedAt().toString());
        }
        return lines(fields);
    }

    static SharedShardAllocationRequest decodeSharedShardAllocationRequest(ConsumerRecord<String, String> record) {
        Map<String, String> fields = fields(record.value());
        return new SharedShardAllocationRequest(
                new ExperienceId(required(fields, "experienceId")),
                new PoolId(required(fields, "poolId")),
                new SessionId(optional(fields, "sessionId").orElse(record.key())),
                new ResolvedManifestId(required(fields, "resolvedManifestId")),
                decodeTrace(fields),
                instant(fields, "requestedAt"));
    }

    static String encodeSharedShardAllocationRequest(SharedShardAllocationRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("experienceId", request.experienceId().value());
        fields.put("poolId", request.poolId().value());
        fields.put("sessionId", request.sessionId().value());
        fields.put("resolvedManifestId", request.resolvedManifestId().value());
        encodeTrace(fields, request.traceEnvelope());
        fields.put("requestedAt", request.requestedAt().toString());
        return lines(fields);
    }

    private static <T extends CommandPayload> CommandEnvelope<T> envelope(
            Map<String, String> fields,
            ConsumerRecord<String, String> record,
            ContractName contract,
            CommandName commandName,
            TraceEnvelope trace,
            T payload) {
        return new CommandEnvelope<>(
                new CommandId(required(fields, "commandId")),
                new IdempotencyKey(required(fields, "idempotencyKey")),
                new PrincipalId(required(fields, "principalId")),
                new AggregateId(optional(fields, "aggregateId").orElse(record.key())),
                new ContractName(optional(fields, "contractName").orElse(contract.value())),
                new CommandName(required(fields, "commandName")),
                trace,
                optionalInstant(fields, "deadlineAt"),
                payload);
    }

    private static Map<String, String> commandFields(
            CommandEnvelope<?> envelope,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            Optional<Revision> expectedRevision,
            String payloadFingerprint,
            Instant receivedAt) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("commandId", envelope.commandId().value());
        fields.put("idempotencyKey", envelope.idempotencyKey().value());
        fields.put("principalId", envelope.principalId().value());
        fields.put("aggregateId", envelope.aggregateId().value());
        fields.put("contractName", envelope.contractName().value());
        fields.put("commandName", envelope.commandName().value());
        encodeTrace(fields, envelope.traceEnvelope());
        fields.put("deadlineAt", envelope.deadlineAt().map(Instant::toString).orElse(""));
        fields.put("authenticatedPrincipal", authenticatedPrincipal.value());
        fields.put("fencingEpoch", Long.toString(fencingEpoch));
        fields.put("expectedRevision", expectedRevision.map(value -> Long.toString(value.value())).orElse(""));
        fields.put("payloadFingerprint", payloadFingerprint);
        fields.put("receivedAt", receivedAt.toString());
        return fields;
    }

    private static void encodeTrace(Map<String, String> fields, TraceEnvelope trace) {
        fields.put("traceId", trace.traceId());
        fields.put("spanId", trace.spanId());
        fields.put("parentSpanId", trace.parentSpanId().orElse(""));
        fields.put("traceCreatedAt", trace.createdAt().toString());
        fields.put("originService", trace.originService());
        fields.put("originInstanceId", trace.originInstanceId().value());
    }

    private static TraceEnvelope decodeTrace(Map<String, String> fields) {
        return new TraceEnvelope(
                required(fields, "traceId"),
                required(fields, "spanId"),
                optional(fields, "parentSpanId"),
                instant(fields, "traceCreatedAt"),
                required(fields, "originService"),
                new InstanceId(required(fields, "originInstanceId")));
    }

    private static void requireCommand(Map<String, String> fields, CommandName expected) {
        String actual = required(fields, "commandName");
        if (!expected.value().equals(actual)) {
            throw new IllegalArgumentException("Unsupported control command " + actual);
        }
    }

    private static PrincipalId authenticatedPrincipal(Map<String, String> fields) {
        return new PrincipalId(required(fields, "authenticatedPrincipal"));
    }

    private static List<SubjectId> subjectIds(Map<String, String> fields, String key) {
        return Arrays.stream(required(fields, key).split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> new SubjectId(UUID.fromString(value)))
                .toList();
    }

    private static List<InstanceId> instanceIds(Map<String, String> fields, String key) {
        return Arrays.stream(required(fields, key).split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(InstanceId::new)
                .toList();
    }

    private static String joinSubjectIds(List<SubjectId> values) {
        return values.stream()
                .map(value -> value.value().toString())
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String joinInstanceIds(List<InstanceId> values) {
        return values.stream()
                .map(InstanceId::value)
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static Map<String, String> fields(String payload) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (payload == null || payload.isBlank()) {
            return fields;
        }
        for (String line : payload.split("\\R")) {
            int separator = line.indexOf('=');
            if (separator < 1) {
                throw new IllegalArgumentException("Malformed control command wire line: " + line);
            }
            fields.put(line.substring(0, separator), line.substring(separator + 1));
        }
        return fields;
    }

    private static String lines(Map<String, String> fields) {
        StringBuilder builder = new StringBuilder();
        fields.forEach((key, value) -> builder.append(key).append('=').append(value == null ? "" : value).append('\n'));
        return builder.toString();
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing control command wire field " + key);
        }
        return value;
    }

    private static Optional<String> optional(Map<String, String> fields, String key) {
        String value = fields.get(key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static int intValue(Map<String, String> fields, String key) {
        return Integer.parseInt(required(fields, key));
    }

    private static boolean booleanValue(Map<String, String> fields, String key) {
        return Boolean.parseBoolean(required(fields, key));
    }

    private static long longValue(Map<String, String> fields, String key) {
        return Long.parseLong(required(fields, key));
    }

    private static Optional<Long> optionalLong(Map<String, String> fields, String key) {
        return optional(fields, key).map(Long::parseLong);
    }

    private static Optional<Revision> optionalRevision(Map<String, String> fields, String key) {
        return optionalLong(fields, key).map(Revision::new);
    }

    private static Instant instant(Map<String, String> fields, String key) {
        return Instant.parse(required(fields, key));
    }

    private static Optional<Instant> optionalInstant(Map<String, String> fields, String key) {
        return optional(fields, key).map(Instant::parse);
    }
}

record SharedShardPlacementWireRequest(
        SharedShardPlacementRequest request,
        List<SharedShardPlacementCandidate> candidates) {
    SharedShardPlacementWireRequest {
        request = Objects.requireNonNull(request, "request");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
    }
}
