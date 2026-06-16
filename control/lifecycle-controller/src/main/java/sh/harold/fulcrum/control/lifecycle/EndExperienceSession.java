package sh.harold.fulcrum.control.lifecycle;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.SessionId;

import java.time.Instant;
import java.util.Objects;

public record EndExperienceSession(
        SessionId sessionId,
        String endReason,
        Instant endedAt,
        TraceEnvelope traceEnvelope) implements ExperienceSessionCommand {
    public EndExperienceSession {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        endReason = ControlLifecycleStrings.requireNonBlank(endReason, "endReason");
        endedAt = Objects.requireNonNull(endedAt, "endedAt");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }
}
