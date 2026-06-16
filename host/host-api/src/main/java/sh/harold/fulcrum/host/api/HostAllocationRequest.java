package sh.harold.fulcrum.host.api;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SessionId;

import java.time.Instant;
import java.util.Objects;

public record HostAllocationRequest(
        PoolId poolId,
        SessionId sessionId,
        ResolvedManifestId resolvedManifestId,
        TraceEnvelope traceEnvelope,
        Instant requestedAt) {
    public HostAllocationRequest {
        poolId = Objects.requireNonNull(poolId, "poolId");
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        resolvedManifestId = Objects.requireNonNull(resolvedManifestId, "resolvedManifestId");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
    }
}
