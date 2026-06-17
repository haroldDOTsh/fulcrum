package sh.harold.fulcrum.control.instance;

import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;

import java.util.Objects;

public record SharedShardExperienceDescriptor(
        ExperienceId experienceId,
        ExperienceShape shape,
        SharedShardPoolDescriptor poolDescriptor,
        ResolvedManifestId resolvedManifestId) {
    public SharedShardExperienceDescriptor {
        experienceId = Objects.requireNonNull(experienceId, "experienceId");
        shape = Objects.requireNonNull(shape, "shape");
        if (shape != ExperienceShape.SHARED_SHARD) {
            throw new IllegalArgumentException("shared-shard placement requires shared-shard shape");
        }
        poolDescriptor = Objects.requireNonNull(poolDescriptor, "poolDescriptor");
        resolvedManifestId = Objects.requireNonNull(resolvedManifestId, "resolvedManifestId");
    }

    public PoolId poolId() {
        return poolDescriptor.poolId();
    }

    public int hardCapacity() {
        return poolDescriptor.hardCapacity();
    }
}
