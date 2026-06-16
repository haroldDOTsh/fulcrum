package sh.harold.fulcrum.data.session;

import sh.harold.fulcrum.api.kernel.SessionId;

import java.time.Instant;
import java.util.Objects;

public record ActivateSession(
        SessionId sessionId,
        SessionOwnerToken ownerToken,
        long ownerEpoch,
        Instant activatedAt,
        Instant leaseExpiresAt) implements SessionCommand {
    public ActivateSession {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        ownerToken = Objects.requireNonNull(ownerToken, "ownerToken");
        if (ownerEpoch <= 0) {
            throw new IllegalArgumentException("ownerEpoch must be positive");
        }
        activatedAt = Objects.requireNonNull(activatedAt, "activatedAt");
        leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        if (!leaseExpiresAt.isAfter(activatedAt)) {
            throw new IllegalArgumentException("leaseExpiresAt must be after activatedAt");
        }
    }
}
