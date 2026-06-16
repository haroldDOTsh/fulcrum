package sh.harold.fulcrum.control.lifecycle;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.SessionId;

import java.time.Instant;
import java.util.Objects;

public record ActivateExperienceSession(
        SessionId sessionId,
        Instant activatedAt,
        TraceEnvelope traceEnvelope) implements ExperienceSessionCommand {
    public ActivateExperienceSession {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        activatedAt = Objects.requireNonNull(activatedAt, "activatedAt");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }
}
