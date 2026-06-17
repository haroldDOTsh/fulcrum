package sh.harold.fulcrum.host.api;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;

import java.time.Instant;
import java.util.Objects;

public record HostAllocationClaim(
        SlotId slotId,
        SessionId sessionId,
        HostInstanceIdentity instanceIdentity,
        ResolvedManifestId resolvedManifestId,
        HostNetworkEndpoint minecraftEndpoint,
        TraceEnvelope traceEnvelope,
        Instant allocatedAt) {
    public HostAllocationClaim {
        slotId = Objects.requireNonNull(slotId, "slotId");
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        instanceIdentity = Objects.requireNonNull(instanceIdentity, "instanceIdentity");
        resolvedManifestId = Objects.requireNonNull(resolvedManifestId, "resolvedManifestId");
        minecraftEndpoint = Objects.requireNonNull(minecraftEndpoint, "minecraftEndpoint");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        allocatedAt = Objects.requireNonNull(allocatedAt, "allocatedAt");
    }
}
