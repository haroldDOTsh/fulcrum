package sh.harold.fulcrum.control.instance;

import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record InstanceSnapshot(
        InstanceId instanceId,
        String instanceKind,
        PoolId poolId,
        MachineRef machineRef,
        PrincipalId instancePrincipalId,
        Optional<ResolvedManifestId> resolvedManifestId,
        InstanceRegistryStatus status,
        Optional<String> statusReason,
        TraceEnvelope traceEnvelope,
        Instant updatedAt) {
    public InstanceSnapshot {
        instanceId = Objects.requireNonNull(instanceId, "instanceId");
        instanceKind = ControlInstanceStrings.requireNonBlank(instanceKind, "instanceKind");
        poolId = Objects.requireNonNull(poolId, "poolId");
        machineRef = Objects.requireNonNull(machineRef, "machineRef");
        instancePrincipalId = Objects.requireNonNull(instancePrincipalId, "instancePrincipalId");
        resolvedManifestId = resolvedManifestId == null ? Optional.empty() : resolvedManifestId;
        status = Objects.requireNonNull(status, "status");
        statusReason = statusReason == null
                ? Optional.empty()
                : statusReason.map(reason -> ControlInstanceStrings.requireNonBlank(reason, "statusReason"));
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static InstanceSnapshot from(RegisterInstance command) {
        return new InstanceSnapshot(
                command.instanceId(),
                command.instanceKind(),
                command.poolId(),
                command.machineRef(),
                command.instancePrincipalId(),
                Optional.empty(),
                InstanceRegistryStatus.REGISTERED,
                Optional.empty(),
                command.traceEnvelope(),
                command.registeredAt());
    }

    InstanceSnapshot ready(MarkInstanceReady command) {
        return transition(
                Optional.of(command.resolvedManifestId()),
                InstanceRegistryStatus.READY,
                Optional.empty(),
                command.traceEnvelope(),
                command.readyAt());
    }

    InstanceSnapshot draining(MarkInstanceDraining command) {
        return transition(
                resolvedManifestId,
                InstanceRegistryStatus.DRAINING,
                Optional.of(command.reason()),
                command.traceEnvelope(),
                command.drainingAt());
    }

    InstanceSnapshot offline(MarkInstanceOffline command) {
        return transition(
                resolvedManifestId,
                InstanceRegistryStatus.OFFLINE,
                Optional.of(command.reason()),
                command.traceEnvelope(),
                command.offlineAt());
    }

    private InstanceSnapshot transition(
            Optional<ResolvedManifestId> nextResolvedManifestId,
            InstanceRegistryStatus nextStatus,
            Optional<String> nextReason,
            TraceEnvelope nextTraceEnvelope,
            Instant nextUpdatedAt) {
        return new InstanceSnapshot(
                instanceId,
                instanceKind,
                poolId,
                machineRef,
                instancePrincipalId,
                nextResolvedManifestId,
                nextStatus,
                nextReason,
                nextTraceEnvelope,
                nextUpdatedAt);
    }

    public String wireValue(Revision revision) {
        return "instanceId=" + instanceId.value()
                + "|instanceKind=" + instanceKind
                + "|poolId=" + poolId.value()
                + "|machineRef=" + machineRef.value()
                + "|principalId=" + instancePrincipalId.value()
                + "|resolvedManifestId=" + resolvedManifestId.map(ResolvedManifestId::value).orElse("none")
                + "|status=" + status.name()
                + "|reason=" + statusReason.orElse("none")
                + "|revision=" + revision.value()
                + "|traceId=" + traceEnvelope.traceId();
    }
}
