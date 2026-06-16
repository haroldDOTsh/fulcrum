package sh.harold.fulcrum.data.presence;

import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.PresenceId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record PresenceSnapshot(
        PresenceId presenceId,
        SubjectId subjectId,
        InstanceId ownerInstanceId,
        Optional<SessionId> sessionId,
        Optional<RouteId> routeId,
        Instant observedAt,
        Instant expiresAt) {
    public PresenceSnapshot {
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

    static PresenceSnapshot from(ClaimPresence command) {
        return new PresenceSnapshot(
                command.presenceId(),
                command.subjectId(),
                command.ownerInstanceId(),
                command.sessionId(),
                command.routeId(),
                command.observedAt(),
                command.expiresAt());
    }

    String wireValue() {
        return "presenceId=" + presenceId.value()
                + "\nsubjectId=" + subjectId.value()
                + "\nownerInstanceId=" + ownerInstanceId.value()
                + "\nsessionId=" + sessionId.map(SessionId::value).orElse("")
                + "\nrouteId=" + routeId.map(RouteId::value).orElse("")
                + "\nobservedAt=" + observedAt
                + "\nexpiresAt=" + expiresAt;
    }
}
