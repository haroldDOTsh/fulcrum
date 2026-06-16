package sh.harold.fulcrum.data.session;

import sh.harold.fulcrum.api.kernel.SessionId;

import java.time.Instant;
import java.util.Objects;

public record HeartbeatSession(
        SessionId sessionId,
        SessionOwnerToken ownerToken,
        long ownerEpoch,
        Instant observedAt,
        Instant leaseExpiresAt) implements SessionCommand {
    public HeartbeatSession {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        ownerToken = Objects.requireNonNull(ownerToken, "ownerToken");
        if (ownerEpoch <= 0) {
            throw new IllegalArgumentException("ownerEpoch must be positive");
        }
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        if (!leaseExpiresAt.isAfter(observedAt)) {
            throw new IllegalArgumentException("leaseExpiresAt must be after observedAt");
        }
    }
}
