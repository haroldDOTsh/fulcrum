package sh.harold.fulcrum.data.presence;

import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.PresenceId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record ClaimPresence(
        PresenceId presenceId,
        SubjectId subjectId,
        InstanceId ownerInstanceId,
        Optional<SessionId> sessionId,
        Optional<RouteId> routeId,
        Instant observedAt,
        Instant expiresAt) implements CommandPayload {
    public ClaimPresence {
        presenceId = Objects.requireNonNull(presenceId, "presenceId");
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        ownerInstanceId = Objects.requireNonNull(ownerInstanceId, "ownerInstanceId");
        sessionId = sessionId == null ? Optional.empty() : sessionId;
        routeId = routeId == null ? Optional.empty() : routeId;
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(observedAt)) {
            throw new IllegalArgumentException("expiresAt must be after observedAt");
        }
    }
}
