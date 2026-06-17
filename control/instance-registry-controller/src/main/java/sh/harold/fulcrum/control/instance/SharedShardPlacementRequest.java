package sh.harold.fulcrum.control.instance;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Objects;

public record SharedShardPlacementRequest(
        ExperienceId experienceId,
        PoolId poolId,
        ResolvedManifestId resolvedManifestId,
        SubjectId subjectId,
        int hardCapacity,
        Instant requestedAt,
        TraceEnvelope traceEnvelope) {
    public SharedShardPlacementRequest {
        experienceId = Objects.requireNonNull(experienceId, "experienceId");
        poolId = Objects.requireNonNull(poolId, "poolId");
        resolvedManifestId = Objects.requireNonNull(resolvedManifestId, "resolvedManifestId");
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        if (hardCapacity <= 0) {
            throw new IllegalArgumentException("hardCapacity must be positive");
        }
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }
}
