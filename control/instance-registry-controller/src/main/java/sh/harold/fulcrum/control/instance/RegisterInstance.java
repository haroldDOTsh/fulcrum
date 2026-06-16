package sh.harold.fulcrum.control.instance;

import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;

import java.time.Instant;
import java.util.Objects;

public record RegisterInstance(
        InstanceId instanceId,
        String instanceKind,
        PoolId poolId,
        MachineRef machineRef,
        PrincipalId instancePrincipalId,
        Instant registeredAt,
        TraceEnvelope traceEnvelope) implements InstanceRegistryCommand {
    public RegisterInstance {
        instanceId = Objects.requireNonNull(instanceId, "instanceId");
        instanceKind = ControlInstanceStrings.requireNonBlank(instanceKind, "instanceKind");
        poolId = Objects.requireNonNull(poolId, "poolId");
        machineRef = Objects.requireNonNull(machineRef, "machineRef");
        instancePrincipalId = Objects.requireNonNull(instancePrincipalId, "instancePrincipalId");
        registeredAt = Objects.requireNonNull(registeredAt, "registeredAt");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }
}
