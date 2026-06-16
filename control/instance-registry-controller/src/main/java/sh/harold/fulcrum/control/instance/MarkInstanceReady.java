package sh.harold.fulcrum.control.instance;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;

import java.time.Instant;
import java.util.Objects;

public record MarkInstanceReady(
        InstanceId instanceId,
        ResolvedManifestId resolvedManifestId,
        Instant readyAt,
        TraceEnvelope traceEnvelope) implements InstanceRegistryCommand {
    public MarkInstanceReady {
        instanceId = Objects.requireNonNull(instanceId, "instanceId");
        resolvedManifestId = Objects.requireNonNull(resolvedManifestId, "resolvedManifestId");
        readyAt = Objects.requireNonNull(readyAt, "readyAt");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }
}
