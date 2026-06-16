package sh.harold.fulcrum.control.lifecycle;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SessionId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record RecordLifecycleObservation(
        LifecycleTraceId traceId,
        LifecyclePhase phase,
        String aggregateType,
        String aggregateId,
        Optional<SessionId> sessionId,
        Optional<ResolvedManifestId> resolvedManifestId,
        Instant observedAt,
        TraceEnvelope traceEnvelope) implements LifecycleTraceCommand {
    public RecordLifecycleObservation {
        traceId = Objects.requireNonNull(traceId, "traceId");
        phase = Objects.requireNonNull(phase, "phase");
        aggregateType = ControlLifecycleStrings.requireNonBlank(aggregateType, "aggregateType");
        aggregateId = ControlLifecycleStrings.requireNonBlank(aggregateId, "aggregateId");
        sessionId = sessionId == null ? Optional.empty() : sessionId;
        resolvedManifestId = resolvedManifestId == null ? Optional.empty() : resolvedManifestId;
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        if (!traceId.value().equals(traceEnvelope.traceId())) {
            throw new IllegalArgumentException("traceId must match traceEnvelope traceId");
        }
    }
}
