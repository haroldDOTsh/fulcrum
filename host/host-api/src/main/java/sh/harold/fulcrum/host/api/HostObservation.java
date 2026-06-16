package sh.harold.fulcrum.host.api;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record HostObservation(
        InstanceId instanceId,
        String observationType,
        TraceEnvelope traceEnvelope,
        Instant observedAt,
        Map<String, String> attributes) {
    public HostObservation {
        instanceId = Objects.requireNonNull(instanceId, "instanceId");
        observationType = HostNames.requireNonBlank(observationType, "observationType");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        attributes = checkedAttributes(attributes);
    }

    public HostObservation(
            InstanceId instanceId,
            String observationType,
            TraceEnvelope traceEnvelope,
            Instant observedAt) {
        this(instanceId, observationType, traceEnvelope, observedAt, Map.of());
    }

    private static Map<String, String> checkedAttributes(Map<String, String> attributes) {
        Map<String, String> checked = new LinkedHashMap<>();
        Objects.requireNonNull(attributes, "attributes").forEach((key, value) -> checked.put(
                HostNames.requireNonBlank(key, "attribute key"),
                HostNames.requireNonBlank(value, "attribute value")));
        return Map.copyOf(checked);
    }
}
