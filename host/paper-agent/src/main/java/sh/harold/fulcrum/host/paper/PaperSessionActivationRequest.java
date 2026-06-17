package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.SessionId;

import java.time.Instant;
import java.util.Objects;

public record PaperSessionActivationRequest(
        SessionId sessionId,
        String ownerToken,
        long ownerEpoch,
        Instant activatedAt,
        Instant leaseExpiresAt,
        TraceEnvelope traceEnvelope) {
    public PaperSessionActivationRequest {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        ownerToken = PaperArtifactNames.requireNonBlank(ownerToken, "ownerToken");
        if (ownerEpoch <= 0) {
            throw new IllegalArgumentException("ownerEpoch must be positive");
        }
        activatedAt = Objects.requireNonNull(activatedAt, "activatedAt");
        leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        if (!leaseExpiresAt.isAfter(activatedAt)) {
            throw new IllegalArgumentException("leaseExpiresAt must be after activatedAt");
        }
    }
}
