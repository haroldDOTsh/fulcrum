package sh.harold.fulcrum.distribution.launcher;

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
import sh.harold.fulcrum.control.capability.CapabilityBinding;
import sh.harold.fulcrum.control.capability.CapabilityEnablementControlRecord;
import sh.harold.fulcrum.control.capability.CapabilityEnablementState;
import sh.harold.fulcrum.control.fault.FaultControlRecord;
import sh.harold.fulcrum.control.fault.FaultId;
import sh.harold.fulcrum.control.fault.FaultRecord;
import sh.harold.fulcrum.control.fault.FaultTargetType;
import sh.harold.fulcrum.control.fault.QuarantineState;
import sh.harold.fulcrum.control.instance.InstanceRegistryRecord;
import sh.harold.fulcrum.control.instance.InstanceRegistryStatus;
import sh.harold.fulcrum.control.instance.InstanceSnapshot;
import sh.harold.fulcrum.control.instance.ExperienceShape;
import sh.harold.fulcrum.control.instance.SharedShardExperienceDescriptor;
import sh.harold.fulcrum.control.instance.SharedShardPlacementDecision;
import sh.harold.fulcrum.control.instance.SharedShardPlacementDecisionStatus;
import sh.harold.fulcrum.control.instance.SharedShardPlacementRequest;
import sh.harold.fulcrum.control.instance.SharedShardPoolDescriptor;
import sh.harold.fulcrum.control.lifecycle.ExperienceSessionControlRecord;
import sh.harold.fulcrum.control.lifecycle.ExperienceSessionRecord;
import sh.harold.fulcrum.control.lifecycle.ExperienceSessionStatus;
import sh.harold.fulcrum.control.lifecycle.LifecyclePhase;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceControlRecord;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceEntry;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceId;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceRecord;
import sh.harold.fulcrum.control.queue.QueueIntentId;
import sh.harold.fulcrum.control.queue.QueueIntentSnapshot;
import sh.harold.fulcrum.control.queue.QueueIntentStatus;
import sh.harold.fulcrum.control.queue.QueueRosterControlRecord;
import sh.harold.fulcrum.control.queue.QueueRosterState;
import sh.harold.fulcrum.control.queue.RosterIntentId;
import sh.harold.fulcrum.control.queue.RosterIntentSnapshot;
import sh.harold.fulcrum.control.queue.RosterIntentStatus;
import sh.harold.fulcrum.control.route.RouteAttemptControlRecord;
import sh.harold.fulcrum.control.route.RouteAttemptId;
import sh.harold.fulcrum.control.route.RouteAttemptLifecycleStatus;
import sh.harold.fulcrum.control.route.RouteAttemptSnapshot;
import sh.harold.fulcrum.host.api.HostAllocationClaim;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostNetworkEndpoint;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

final class ControllerStateWireCodec {
    private static final String RECORD_TYPE = "recordType";

    private ControllerStateWireCodec() {
    }

    static boolean isRecordType(String payload, String type) {
        return type.equals(fields(payload).get(RECORD_TYPE));
    }

    static String encodeInstanceRegistry(InstanceRegistryRecord record) {
        Map<String, String> fields = base("instance-registry", record.revision(), record.fencingEpoch());
        fields.put("snapshot", Boolean.toString(record.snapshot().isPresent()));
        record.snapshot().ifPresent(snapshot -> {
            fields.put("instanceId", snapshot.instanceId().value());
            fields.put("instanceKind", snapshot.instanceKind());
            fields.put("poolId", snapshot.poolId().value());
            fields.put("machineRef", snapshot.machineRef().value());
            fields.put("instancePrincipalId", snapshot.instancePrincipalId().value());
            fields.put("resolvedManifestId", snapshot.resolvedManifestId().map(ResolvedManifestId::value).orElse(""));
            fields.put("status", snapshot.status().name());
            fields.put("statusReason", snapshot.statusReason().orElse(""));
            fields.put("updatedAt", snapshot.updatedAt().toString());
            encodeTrace(fields, "trace", snapshot.traceEnvelope());
        });
        return lines(fields);
    }

    static InstanceRegistryRecord decodeInstanceRegistry(String payload) {
        Map<String, String> fields = fields(payload);
        requireType(fields, "instance-registry");
        if (!booleanValue(fields, "snapshot")) {
            return InstanceRegistryRecord.empty(longValue(fields, "fencingEpoch"));
        }
        InstanceSnapshot snapshot = new InstanceSnapshot(
                new InstanceId(required(fields, "instanceId")),
                required(fields, "instanceKind"),
                new PoolId(required(fields, "poolId")),
                new MachineRef(required(fields, "machineRef")),
                new PrincipalId(required(fields, "instancePrincipalId")),
                optional(fields, "resolvedManifestId").map(ResolvedManifestId::new),
                InstanceRegistryStatus.valueOf(required(fields, "status")),
                optional(fields, "statusReason"),
                decodeTrace(fields, "trace"),
                instant(fields, "updatedAt"));
        return new InstanceRegistryRecord(revision(fields), longValue(fields, "fencingEpoch"), Optional.of(snapshot));
    }

    static String encodeRouteAttempt(RouteAttemptControlRecord record) {
        Map<String, String> fields = base("route-attempt", record.revision(), record.fencingEpoch());
        fields.put("snapshot", Boolean.toString(record.snapshot().isPresent()));
        record.snapshot().ifPresent(snapshot -> {
            fields.put("routeAttemptId", snapshot.routeAttemptId().value());
            fields.put("routeId", snapshot.routeId().value());
            fields.put("sessionId", snapshot.sessionId().value());
            fields.put("allocationSlotId", snapshot.allocationSlotId().value());
            fields.put("subjectIds", joinSubjectIds(snapshot.subjectIds()));
            fields.put("proxyInstanceIds", joinInstanceIds(snapshot.proxyInstanceIds()));
            fields.put("sourcePresenceId", snapshot.sourcePresenceId().value());
            fields.put("targetInstanceId", snapshot.targetInstanceId().value());
            fields.put("targetResolvedManifestId", snapshot.targetResolvedManifestId().value());
            fields.put("deadlineAt", snapshot.deadlineAt().toString());
            fields.put("retryCount", Integer.toString(snapshot.retryCount()));
            fields.put("status", snapshot.status().name());
            fields.put("failureReason", snapshot.failureReason().orElse(""));
            fields.put("updatedAt", snapshot.updatedAt().toString());
            encodeTrace(fields, "trace", snapshot.traceEnvelope());
        });
        return lines(fields);
    }

    static RouteAttemptControlRecord decodeRouteAttempt(String payload) {
        Map<String, String> fields = fields(payload);
        requireType(fields, "route-attempt");
        if (!booleanValue(fields, "snapshot")) {
            return RouteAttemptControlRecord.empty(longValue(fields, "fencingEpoch"));
        }
        RouteAttemptSnapshot snapshot = new RouteAttemptSnapshot(
                new RouteAttemptId(required(fields, "routeAttemptId")),
                new RouteId(required(fields, "routeId")),
                new SessionId(required(fields, "sessionId")),
                new SlotId(required(fields, "allocationSlotId")),
                subjectIds(fields, "subjectIds"),
                instanceIds(fields, "proxyInstanceIds"),
                new PresenceId(required(fields, "sourcePresenceId")),
                new InstanceId(required(fields, "targetInstanceId")),
                new ResolvedManifestId(required(fields, "targetResolvedManifestId")),
                instant(fields, "deadlineAt"),
                intValue(fields, "retryCount"),
                RouteAttemptLifecycleStatus.valueOf(required(fields, "status")),
                optional(fields, "failureReason"),
                decodeTrace(fields, "trace"),
                instant(fields, "updatedAt"));
        return new RouteAttemptControlRecord(revision(fields), longValue(fields, "fencingEpoch"), Optional.of(snapshot));
    }

    static String encodeExperienceSession(ExperienceSessionControlRecord record) {
        Map<String, String> fields = base("experience-session", record.revision(), record.fencingEpoch());
        fields.put("sessionRecord", Boolean.toString(record.sessionRecord().isPresent()));
        record.sessionRecord().ifPresent(session -> {
            fields.put("sessionId", session.sessionId().value());
            fields.put("experienceId", session.experienceId().value());
            fields.put("modeId", session.modeId().orElse(""));
            fields.put("sessionType", session.sessionType());
            fields.put("subjectIds", joinSubjectIds(session.subjectIds()));
            fields.put("allocationSlotId", session.allocationSlotId().map(SlotId::value).orElse(""));
            fields.put("instanceId", session.instanceId().map(InstanceId::value).orElse(""));
            fields.put("resolvedManifestId", session.resolvedManifestId().map(ResolvedManifestId::value).orElse(""));
            fields.put("status", session.status().name());
            fields.put("createdAt", session.createdAt().toString());
            fields.put("activatedAt", session.activatedAt().map(Instant::toString).orElse(""));
            fields.put("endedAt", session.endedAt().map(Instant::toString).orElse(""));
            fields.put("endReason", session.endReason().orElse(""));
            fields.put("updatedAt", session.updatedAt().toString());
            encodeTrace(fields, "trace", session.traceEnvelope());
        });
        return lines(fields);
    }

    static ExperienceSessionControlRecord decodeExperienceSession(String payload) {
        Map<String, String> fields = fields(payload);
        requireType(fields, "experience-session");
        if (!booleanValue(fields, "sessionRecord")) {
            return ExperienceSessionControlRecord.empty(longValue(fields, "fencingEpoch"));
        }
        ExperienceSessionRecord session = new ExperienceSessionRecord(
                new SessionId(required(fields, "sessionId")),
                new ExperienceId(required(fields, "experienceId")),
                optional(fields, "modeId"),
                required(fields, "sessionType"),
                subjectIds(fields, "subjectIds"),
                optional(fields, "allocationSlotId").map(SlotId::new),
                optional(fields, "instanceId").map(InstanceId::new),
                optional(fields, "resolvedManifestId").map(ResolvedManifestId::new),
                ExperienceSessionStatus.valueOf(required(fields, "status")),
                instant(fields, "createdAt"),
                optionalInstant(fields, "activatedAt"),
                optionalInstant(fields, "endedAt"),
                optional(fields, "endReason"),
                decodeTrace(fields, "trace"),
                instant(fields, "updatedAt"));
        return new ExperienceSessionControlRecord(revision(fields), longValue(fields, "fencingEpoch"), Optional.of(session));
    }

    static String encodeLifecycleTrace(LifecycleTraceControlRecord record) {
        Map<String, String> fields = base("lifecycle-trace", record.revision(), record.fencingEpoch());
        fields.put("traceId", record.traceRecord().traceId().value());
        fields.put("entryCount", Integer.toString(record.traceRecord().entries().size()));
        for (int index = 0; index < record.traceRecord().entries().size(); index++) {
            LifecycleTraceEntry entry = record.traceRecord().entries().get(index);
            String prefix = "entry." + index + ".";
            fields.put(prefix + "sequence", Integer.toString(entry.sequence()));
            fields.put(prefix + "phase", entry.phase().name());
            fields.put(prefix + "aggregateType", entry.aggregateType());
            fields.put(prefix + "aggregateId", entry.aggregateId());
            fields.put(prefix + "sessionId", entry.sessionId().map(SessionId::value).orElse(""));
            fields.put(prefix + "resolvedManifestId", entry.resolvedManifestId().map(ResolvedManifestId::value).orElse(""));
            fields.put(prefix + "observedAt", entry.observedAt().toString());
            encodeTrace(fields, prefix + "trace", entry.traceEnvelope());
        }
        return lines(fields);
    }

    static LifecycleTraceControlRecord decodeLifecycleTrace(String payload) {
        Map<String, String> fields = fields(payload);
        requireType(fields, "lifecycle-trace");
        LifecycleTraceId traceId = new LifecycleTraceId(required(fields, "traceId"));
        int entryCount = intValue(fields, "entryCount");
        java.util.ArrayList<LifecycleTraceEntry> entries = new java.util.ArrayList<>();
        for (int index = 0; index < entryCount; index++) {
            String prefix = "entry." + index + ".";
            entries.add(new LifecycleTraceEntry(
                    intValue(fields, prefix + "sequence"),
                    LifecyclePhase.valueOf(required(fields, prefix + "phase")),
                    required(fields, prefix + "aggregateType"),
                    required(fields, prefix + "aggregateId"),
                    optional(fields, prefix + "sessionId").map(SessionId::new),
                    optional(fields, prefix + "resolvedManifestId").map(ResolvedManifestId::new),
                    instant(fields, prefix + "observedAt"),
                    decodeTrace(fields, prefix + "trace")));
        }
        return new LifecycleTraceControlRecord(
                revision(fields),
                longValue(fields, "fencingEpoch"),
                new LifecycleTraceRecord(traceId, entries));
    }

    static String encodeCapabilityEnablement(CapabilityEnablementControlRecord record) {
        Map<String, String> fields = base("capability-enablement", record.revision(), record.fencingEpoch());
        fields.put("scope", record.state().scope().value());
        fields.put("bindingCount", Integer.toString(record.state().bindings().size()));
        int index = 0;
        for (CapabilityBinding binding : record.state().bindings().values()) {
            String prefix = "binding." + index + ".";
            fields.put(prefix + "capabilityId", binding.capabilityId().value());
            fields.put(prefix + "enabled", Boolean.toString(binding.enabled()));
            fields.put(prefix + "contractSet", binding.contractSet());
            fields.put(prefix + "reason", binding.reason().orElse(""));
            fields.put(prefix + "changedAt", binding.changedAt().toString());
            encodeTrace(fields, prefix + "trace", binding.traceEnvelope());
            index++;
        }
        return lines(fields);
    }

    static CapabilityEnablementControlRecord decodeCapabilityEnablement(String payload) {
        Map<String, String> fields = fields(payload);
        requireType(fields, "capability-enablement");
        Map<CapabilityId, CapabilityBinding> bindings = new LinkedHashMap<>();
        int bindingCount = intValue(fields, "bindingCount");
        for (int index = 0; index < bindingCount; index++) {
            String prefix = "binding." + index + ".";
            CapabilityBinding binding = new CapabilityBinding(
                    new CapabilityId(required(fields, prefix + "capabilityId")),
                    booleanValue(fields, prefix + "enabled"),
                    required(fields, prefix + "contractSet"),
                    optional(fields, prefix + "reason"),
                    instant(fields, prefix + "changedAt"),
                    decodeTrace(fields, prefix + "trace"));
            bindings.put(binding.capabilityId(), binding);
        }
        return new CapabilityEnablementControlRecord(
                revision(fields),
                longValue(fields, "fencingEpoch"),
                new CapabilityEnablementState(new CapabilityScope(required(fields, "scope")), bindings));
    }

    static String encodeQueueRoster(QueueRosterControlRecord record) {
        Map<String, String> fields = base("queue-roster", record.revision(), record.fencingEpoch());
        fields.put("queueIntentCount", Integer.toString(record.state().queueIntents().size()));
        int queueIndex = 0;
        for (QueueIntentSnapshot intent : record.state().queueIntents().values()) {
            String prefix = "queue." + queueIndex + ".";
            fields.put(prefix + "queueIntentId", intent.queueIntentId().value());
            fields.put(prefix + "subjectIds", joinSubjectIds(intent.subjectIds()));
            fields.put(prefix + "experienceId", intent.experienceId().value());
            fields.put(prefix + "modeId", intent.modeId().orElse(""));
            fields.put(prefix + "poolId", intent.poolId().value());
            fields.put(prefix + "priority", Integer.toString(intent.priority()));
            fields.put(prefix + "createdAt", intent.createdAt().toString());
            fields.put(prefix + "deadlineAt", intent.deadlineAt().toString());
            fields.put(prefix + "status", intent.status().name());
            fields.put(prefix + "rosterIntentId", intent.rosterIntentId().map(RosterIntentId::value).orElse(""));
            fields.put(prefix + "updatedAt", intent.updatedAt().toString());
            encodeTrace(fields, prefix + "trace", intent.traceEnvelope());
            queueIndex++;
        }
        fields.put("rosterIntentCount", Integer.toString(record.state().rosterIntents().size()));
        int rosterIndex = 0;
        for (RosterIntentSnapshot intent : record.state().rosterIntents().values()) {
            String prefix = "roster." + rosterIndex + ".";
            fields.put(prefix + "rosterIntentId", intent.rosterIntentId().value());
            fields.put(prefix + "queueIntentIds", joinQueueIntentIds(intent.queueIntentIds()));
            fields.put(prefix + "subjectIds", joinSubjectIds(intent.subjectIds()));
            fields.put(prefix + "experienceId", intent.experienceId().value());
            fields.put(prefix + "modeId", intent.modeId().orElse(""));
            fields.put(prefix + "poolId", intent.poolId().value());
            fields.put(prefix + "maxSubjects", Integer.toString(intent.maxSubjects()));
            fields.put(prefix + "status", intent.status().name());
            fields.put(prefix + "formedAt", intent.formedAt().toString());
            encodeTrace(fields, prefix + "trace", intent.traceEnvelope());
            rosterIndex++;
        }
        return lines(fields);
    }

    static QueueRosterControlRecord decodeQueueRoster(String payload) {
        Map<String, String> fields = fields(payload);
        requireType(fields, "queue-roster");
        Map<QueueIntentId, QueueIntentSnapshot> queueIntents = new LinkedHashMap<>();
        int queueIntentCount = intValue(fields, "queueIntentCount");
        for (int index = 0; index < queueIntentCount; index++) {
            String prefix = "queue." + index + ".";
            QueueIntentSnapshot intent = new QueueIntentSnapshot(
                    new QueueIntentId(required(fields, prefix + "queueIntentId")),
                    subjectIds(fields, prefix + "subjectIds"),
                    new ExperienceId(required(fields, prefix + "experienceId")),
                    optional(fields, prefix + "modeId"),
                    new PoolId(required(fields, prefix + "poolId")),
                    intValue(fields, prefix + "priority"),
                    instant(fields, prefix + "createdAt"),
                    instant(fields, prefix + "deadlineAt"),
                    QueueIntentStatus.valueOf(required(fields, prefix + "status")),
                    optional(fields, prefix + "rosterIntentId").map(RosterIntentId::new),
                    decodeTrace(fields, prefix + "trace"),
                    instant(fields, prefix + "updatedAt"));
            queueIntents.put(intent.queueIntentId(), intent);
        }
        Map<RosterIntentId, RosterIntentSnapshot> rosterIntents = new LinkedHashMap<>();
        int rosterIntentCount = intValue(fields, "rosterIntentCount");
        for (int index = 0; index < rosterIntentCount; index++) {
            String prefix = "roster." + index + ".";
            RosterIntentSnapshot intent = new RosterIntentSnapshot(
                    new RosterIntentId(required(fields, prefix + "rosterIntentId")),
                    queueIntentIds(fields, prefix + "queueIntentIds"),
                    subjectIds(fields, prefix + "subjectIds"),
                    new ExperienceId(required(fields, prefix + "experienceId")),
                    optional(fields, prefix + "modeId"),
                    new PoolId(required(fields, prefix + "poolId")),
                    intValue(fields, prefix + "maxSubjects"),
                    RosterIntentStatus.valueOf(required(fields, prefix + "status")),
                    decodeTrace(fields, prefix + "trace"),
                    instant(fields, prefix + "formedAt"));
            rosterIntents.put(intent.rosterIntentId(), intent);
        }
        return new QueueRosterControlRecord(
                revision(fields),
                longValue(fields, "fencingEpoch"),
                new QueueRosterState(queueIntents, rosterIntents));
    }

    static String encodeFault(FaultControlRecord record) {
        Map<String, String> fields = base("fault", record.revision(), record.fencingEpoch());
        fields.put("faultRecord", Boolean.toString(record.faultRecord().isPresent()));
        record.faultRecord().ifPresent(fault -> {
            fields.put("faultId", fault.faultId().value());
            fields.put("targetType", fault.targetType().name());
            fields.put("targetId", fault.targetId());
            fields.put("scope", fault.scope());
            fields.put("firstSeenAt", fault.firstSeenAt().toString());
            fields.put("lastSeenAt", fault.lastSeenAt().toString());
            fields.put("count", Integer.toString(fault.count()));
            fields.put("quarantineState", fault.quarantineState().name());
            fields.put("reason", fault.reason());
            encodeTrace(fields, "trace", fault.traceEnvelope());
        });
        return lines(fields);
    }

    static FaultControlRecord decodeFault(String payload) {
        Map<String, String> fields = fields(payload);
        requireType(fields, "fault");
        if (!booleanValue(fields, "faultRecord")) {
            return FaultControlRecord.empty(longValue(fields, "fencingEpoch"));
        }
        FaultRecord fault = new FaultRecord(
                new FaultId(required(fields, "faultId")),
                FaultTargetType.valueOf(required(fields, "targetType")),
                required(fields, "targetId"),
                required(fields, "scope"),
                instant(fields, "firstSeenAt"),
                instant(fields, "lastSeenAt"),
                intValue(fields, "count"),
                QuarantineState.valueOf(required(fields, "quarantineState")),
                required(fields, "reason"),
                decodeTrace(fields, "trace"));
        return new FaultControlRecord(revision(fields), longValue(fields, "fencingEpoch"), Optional.of(fault));
    }

    static String encodeSharedShardPlacement(
            ExternalControllerWorkerCatalog.StoredSharedShardPlacement placement) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(RECORD_TYPE, "shared-shard-placement");
        fields.put("requestFingerprint", placement.requestFingerprint());
        SharedShardPlacementRequest request = placement.request();
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
        encodeTrace(fields, "requestTrace", request.traceEnvelope());
        SharedShardPlacementDecision decision = placement.decision();
        fields.put("status", decision.status().name());
        fields.put("instanceId", decision.instanceId().map(InstanceId::value).orElse(""));
        fields.put("sessionId", decision.sessionId().map(SessionId::value).orElse(""));
        fields.put("slotId", decision.slotId().map(SlotId::value).orElse(""));
        return lines(fields);
    }

    static ExternalControllerWorkerCatalog.StoredSharedShardPlacement decodeSharedShardPlacement(String payload) {
        Map<String, String> fields = fields(payload);
        requireType(fields, "shared-shard-placement");
        SharedShardPlacementRequest request = new SharedShardPlacementRequest(
                new SharedShardExperienceDescriptor(
                        new ExperienceId(required(fields, "experienceId")),
                        ExperienceShape.SHARED_SHARD,
                        new SharedShardPoolDescriptor(
                                new PoolId(required(fields, "poolId")),
                                required(fields, "agonesFleetName"),
                                intValue(fields, "targetCapacity"),
                                intValue(fields, "hardCapacity")),
                        new ResolvedManifestId(required(fields, "resolvedManifestId"))),
                new SubjectId(UUID.fromString(required(fields, "subjectId"))),
                new PresenceId(required(fields, "presenceId")),
                required(fields, "placementAttemptId"),
                optional(fields, "capabilityScopeFingerprint"),
                instant(fields, "requestedAt"),
                decodeTrace(fields, "requestTrace"));
        SharedShardPlacementDecisionStatus status =
                SharedShardPlacementDecisionStatus.valueOf(required(fields, "status"));
        SharedShardPlacementDecision decision = new SharedShardPlacementDecision(
                status,
                optional(fields, "instanceId").map(InstanceId::new),
                optional(fields, "sessionId").map(SessionId::new),
                optional(fields, "slotId").map(SlotId::new),
                request);
        return new ExternalControllerWorkerCatalog.StoredSharedShardPlacement(
                required(fields, "requestFingerprint"),
                request,
                decision);
    }

    static String encodeSharedShardAllocation(
            ExternalControllerWorkerCatalog.StoredSharedShardAllocation allocation) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(RECORD_TYPE, "shared-shard-allocation");
        fields.put("requestFingerprint", allocation.requestFingerprint());
        fields.put("sessionId", allocation.request().sessionId().value());
        fields.put("experienceId", allocation.request().experienceId().value());
        fields.put("poolId", allocation.request().poolId().value());
        fields.put("resolvedManifestId", allocation.request().resolvedManifestId().value());
        fields.put("requestedAt", allocation.request().requestedAt().toString());
        encodeTrace(fields, "requestTrace", allocation.request().traceEnvelope());
        HostAllocationClaim claim = allocation.claim();
        fields.put("slotId", claim.slotId().value());
        fields.put("claimSessionId", claim.sessionId().value());
        fields.put("claimResolvedManifestId", claim.resolvedManifestId().value());
        fields.put("minecraftHost", claim.minecraftEndpoint().host());
        fields.put("minecraftPort", Integer.toString(claim.minecraftEndpoint().port()));
        fields.put("allocatedAt", claim.allocatedAt().toString());
        fields.put("instanceId", claim.instanceIdentity().instanceId().value());
        fields.put("instanceKind", claim.instanceIdentity().instanceKind());
        fields.put("claimPoolId", claim.instanceIdentity().poolId().value());
        fields.put("machineRef", claim.instanceIdentity().machineRef().value());
        fields.put("principalId", claim.instanceIdentity().principalId().value());
        encodeTrace(fields, "claimTrace", claim.traceEnvelope());
        return lines(fields);
    }

    static ExternalControllerWorkerCatalog.StoredSharedShardAllocation decodeSharedShardAllocation(String payload) {
        Map<String, String> fields = fields(payload);
        requireType(fields, "shared-shard-allocation");
        sh.harold.fulcrum.control.allocation.SharedShardAllocationRequest request =
                new sh.harold.fulcrum.control.allocation.SharedShardAllocationRequest(
                        new ExperienceId(required(fields, "experienceId")),
                        new PoolId(required(fields, "poolId")),
                        new SessionId(required(fields, "sessionId")),
                        new ResolvedManifestId(required(fields, "resolvedManifestId")),
                        decodeTrace(fields, "requestTrace"),
                        instant(fields, "requestedAt"));
        HostAllocationClaim claim = new HostAllocationClaim(
                new SlotId(required(fields, "slotId")),
                new SessionId(required(fields, "claimSessionId")),
                new HostInstanceIdentity(
                        new InstanceId(required(fields, "instanceId")),
                        required(fields, "instanceKind"),
                        new PoolId(required(fields, "claimPoolId")),
                        new MachineRef(required(fields, "machineRef")),
                        new PrincipalId(required(fields, "principalId"))),
                new ResolvedManifestId(required(fields, "claimResolvedManifestId")),
                new HostNetworkEndpoint(required(fields, "minecraftHost"), intValue(fields, "minecraftPort")),
                decodeTrace(fields, "claimTrace"),
                instant(fields, "allocatedAt"));
        return new ExternalControllerWorkerCatalog.StoredSharedShardAllocation(
                required(fields, "requestFingerprint"),
                request,
                claim);
    }

    private static Map<String, String> base(String type, Revision revision, long fencingEpoch) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(RECORD_TYPE, type);
        fields.put("revision", Long.toString(Objects.requireNonNull(revision, "revision").value()));
        fields.put("fencingEpoch", Long.toString(fencingEpoch));
        return fields;
    }

    private static void requireType(Map<String, String> fields, String expected) {
        String actual = required(fields, RECORD_TYPE);
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("Expected controller state " + expected + " but got " + actual);
        }
    }

    private static void encodeTrace(Map<String, String> fields, String prefix, TraceEnvelope trace) {
        fields.put(prefix + ".traceId", trace.traceId());
        fields.put(prefix + ".spanId", trace.spanId());
        fields.put(prefix + ".parentSpanId", trace.parentSpanId().orElse(""));
        fields.put(prefix + ".createdAt", trace.createdAt().toString());
        fields.put(prefix + ".originService", trace.originService());
        fields.put(prefix + ".originInstanceId", trace.originInstanceId().value());
    }

    private static TraceEnvelope decodeTrace(Map<String, String> fields, String prefix) {
        return new TraceEnvelope(
                required(fields, prefix + ".traceId"),
                required(fields, prefix + ".spanId"),
                optional(fields, prefix + ".parentSpanId"),
                instant(fields, prefix + ".createdAt"),
                required(fields, prefix + ".originService"),
                new InstanceId(required(fields, prefix + ".originInstanceId")));
    }

    private static String joinSubjectIds(List<SubjectId> values) {
        return values.stream()
                .map(value -> value.value().toString())
                .collect(Collectors.joining(","));
    }

    private static String joinInstanceIds(List<InstanceId> values) {
        return values.stream()
                .map(InstanceId::value)
                .collect(Collectors.joining(","));
    }

    private static String joinQueueIntentIds(List<QueueIntentId> values) {
        return values.stream()
                .map(QueueIntentId::value)
                .collect(Collectors.joining(","));
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

    private static List<QueueIntentId> queueIntentIds(Map<String, String> fields, String key) {
        return Arrays.stream(required(fields, key).split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(QueueIntentId::new)
                .toList();
    }

    private static Map<String, String> fields(String payload) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (payload == null || payload.isBlank()) {
            return fields;
        }
        for (String line : payload.split("\\R")) {
            int separator = line.indexOf('=');
            if (separator < 1) {
                throw new IllegalArgumentException("Malformed controller state wire line: " + line);
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
            throw new IllegalArgumentException("Missing controller state wire field " + key);
        }
        return value;
    }

    private static Optional<String> optional(Map<String, String> fields, String key) {
        String value = fields.get(key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static Revision revision(Map<String, String> fields) {
        return new Revision(longValue(fields, "revision"));
    }

    private static boolean booleanValue(Map<String, String> fields, String key) {
        return Boolean.parseBoolean(required(fields, key));
    }

    private static int intValue(Map<String, String> fields, String key) {
        return Integer.parseInt(required(fields, key));
    }

    private static long longValue(Map<String, String> fields, String key) {
        return Long.parseLong(required(fields, key));
    }

    private static Instant instant(Map<String, String> fields, String key) {
        return Instant.parse(required(fields, key));
    }

    private static Optional<Instant> optionalInstant(Map<String, String> fields, String key) {
        return optional(fields, key).map(Instant::parse);
    }
}
