package sh.harold.fulcrum.api.contract;

import sh.harold.fulcrum.api.kernel.InstanceId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record TraceEnvelope(
        String traceId,
        String spanId,
        Optional<String> parentSpanId,
        Instant createdAt,
        String originService,
        InstanceId originInstanceId) {
    public TraceEnvelope {
        traceId = Names.requireNonBlank(traceId, "traceId");
        spanId = Names.requireNonBlank(spanId, "spanId");
        parentSpanId = parentSpanId == null ? Optional.empty() : parentSpanId.map(String::trim).filter(value -> !value.isEmpty());
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        originService = Names.requireNonBlank(originService, "originService");
        originInstanceId = Objects.requireNonNull(originInstanceId, "originInstanceId");
    }

    public TraceEnvelope child(String childSpanId, Instant childCreatedAt) {
        return new TraceEnvelope(traceId, childSpanId, Optional.of(spanId), childCreatedAt, originService, originInstanceId);
    }
}
