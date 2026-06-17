package sh.harold.fulcrum.control.instance;

import sh.harold.fulcrum.api.kernel.PoolId;

import java.util.Objects;

public record SharedShardPoolDescriptor(
        PoolId poolId,
        String agonesFleetName,
        int targetCapacity,
        int hardCapacity) {
    public SharedShardPoolDescriptor {
        poolId = Objects.requireNonNull(poolId, "poolId");
        agonesFleetName = ControlInstanceStrings.requireNonBlank(agonesFleetName, "agonesFleetName");
        if (targetCapacity <= 0) {
            throw new IllegalArgumentException("targetCapacity must be positive");
        }
        if (hardCapacity < targetCapacity) {
            throw new IllegalArgumentException("hardCapacity must be at least targetCapacity");
        }
    }
}
