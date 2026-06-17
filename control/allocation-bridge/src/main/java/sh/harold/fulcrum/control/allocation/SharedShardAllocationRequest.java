package sh.harold.fulcrum.control.allocation;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SessionId;

import java.time.Instant;
import java.util.Objects;

public record SharedShardAllocationRequest(
        ExperienceId experienceId,
        PoolId poolId,
        SessionId sessionId,
        ResolvedManifestId resolvedManifestId,
        TraceEnvelope traceEnvelope,
        Instant requestedAt) {
    public SharedShardAllocationRequest {
        experienceId = Objects.requireNonNull(experienceId, "experienceId");
        poolId = Objects.requireNonNull(poolId, "poolId");
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        resolvedManifestId = Objects.requireNonNull(resolvedManifestId, "resolvedManifestId");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
    }

    String fingerprint() {
        return experienceId.value()
                + "|" + poolId.value()
                + "|" + sessionId.value()
                + "|" + resolvedManifestId.value();
    }
}
