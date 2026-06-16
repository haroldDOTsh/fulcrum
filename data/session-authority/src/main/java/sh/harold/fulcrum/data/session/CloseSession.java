package sh.harold.fulcrum.data.session;

import sh.harold.fulcrum.api.kernel.SessionId;

import java.time.Instant;
import java.util.Objects;

public record CloseSession(
        SessionId sessionId,
        SessionOwnerToken ownerToken,
        long ownerEpoch,
        Instant closedAt,
        SessionCloseReason reason) implements SessionCommand {
    public CloseSession {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        ownerToken = Objects.requireNonNull(ownerToken, "ownerToken");
        if (ownerEpoch <= 0) {
            throw new IllegalArgumentException("ownerEpoch must be positive");
        }
        closedAt = Objects.requireNonNull(closedAt, "closedAt");
        reason = Objects.requireNonNull(reason, "reason");
        if (reason == SessionCloseReason.LEASE_EXPIRED) {
            throw new IllegalArgumentException("lease expiry must use ExpireSession");
        }
    }
}
