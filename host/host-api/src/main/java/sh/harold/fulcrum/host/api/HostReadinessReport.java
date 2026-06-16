package sh.harold.fulcrum.host.api;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;

import java.time.Instant;
import java.util.Objects;

public record HostReadinessReport(
        HostInstanceIdentity instanceIdentity,
        ResolvedManifestId resolvedManifestId,
        TraceEnvelope traceEnvelope,
        Instant readyAt) {
    public HostReadinessReport {
        instanceIdentity = Objects.requireNonNull(instanceIdentity, "instanceIdentity");
        resolvedManifestId = Objects.requireNonNull(resolvedManifestId, "resolvedManifestId");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        readyAt = Objects.requireNonNull(readyAt, "readyAt");
    }
}
