package sh.harold.fulcrum.control.lifecycle;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SessionId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record LifecycleTraceEntry(
        int sequence,
        LifecyclePhase phase,
        String aggregateType,
        String aggregateId,
        Optional<SessionId> sessionId,
        Optional<ResolvedManifestId> resolvedManifestId,
        Instant observedAt,
        TraceEnvelope traceEnvelope) {
    public LifecycleTraceEntry {
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        phase = Objects.requireNonNull(phase, "phase");
        aggregateType = ControlLifecycleStrings.requireNonBlank(aggregateType, "aggregateType");
        aggregateId = ControlLifecycleStrings.requireNonBlank(aggregateId, "aggregateId");
        sessionId = sessionId == null ? Optional.empty() : sessionId;
        resolvedManifestId = resolvedManifestId == null ? Optional.empty() : resolvedManifestId;
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }

    public static LifecycleTraceEntry from(RecordLifecycleObservation command, int sequence) {
        return new LifecycleTraceEntry(
                sequence,
                command.phase(),
                command.aggregateType(),
                command.aggregateId(),
                command.sessionId(),
                command.resolvedManifestId(),
                command.observedAt(),
                command.traceEnvelope());
    }
}
