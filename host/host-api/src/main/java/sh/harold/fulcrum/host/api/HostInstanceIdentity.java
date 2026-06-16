package sh.harold.fulcrum.host.api;

import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;

import java.util.Objects;

public record HostInstanceIdentity(
        InstanceId instanceId,
        String instanceKind,
        PoolId poolId,
        MachineRef machineRef,
        PrincipalId principalId) {
    public HostInstanceIdentity {
        instanceId = Objects.requireNonNull(instanceId, "instanceId");
        instanceKind = HostNames.requireNonBlank(instanceKind, "instanceKind");
        poolId = Objects.requireNonNull(poolId, "poolId");
        machineRef = Objects.requireNonNull(machineRef, "machineRef");
        principalId = Objects.requireNonNull(principalId, "principalId");
    }
}
