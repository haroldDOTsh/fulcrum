package sh.harold.fulcrum.host.api;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;

import java.time.Instant;
import java.util.Objects;

public record HostObservation(
        InstanceId instanceId,
        String observationType,
        TraceEnvelope traceEnvelope,
        Instant observedAt) {
    public HostObservation {
        instanceId = Objects.requireNonNull(instanceId, "instanceId");
        observationType = HostNames.requireNonBlank(observationType, "observationType");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
    }
}
