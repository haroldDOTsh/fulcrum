package sh.harold.fulcrum.data.session;

import sh.harold.fulcrum.api.kernel.SessionId;

import java.time.Instant;
import java.util.Objects;

public record ExpireSession(
        SessionId sessionId,
        Instant expiredAt) implements SessionCommand {
    public ExpireSession {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        expiredAt = Objects.requireNonNull(expiredAt, "expiredAt");
    }
}
