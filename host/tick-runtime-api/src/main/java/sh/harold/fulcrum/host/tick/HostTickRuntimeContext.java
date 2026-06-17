package sh.harold.fulcrum.host.tick;

import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.host.api.HostSessionAttachment;

import java.util.Objects;
import java.util.Optional;

public record HostTickRuntimeContext(
        SessionId sessionId,
        Optional<HostSessionAttachment> sessionAttachment) {
    public HostTickRuntimeContext {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        sessionAttachment = sessionAttachment == null ? Optional.empty() : sessionAttachment;
        if (sessionAttachment.isPresent()
                && !sessionId.equals(sessionAttachment.orElseThrow().sessionId())) {
            throw new IllegalArgumentException("sessionAttachment must belong to the runtime Session");
        }
    }

    public HostTickRuntimeContext(SessionId sessionId) {
        this(sessionId, Optional.empty());
    }

    public HostTickRuntimeContext(HostSessionAttachment sessionAttachment) {
        this(
                Objects.requireNonNull(sessionAttachment, "sessionAttachment").sessionId(),
                Optional.of(sessionAttachment));
    }
}
