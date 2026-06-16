package sh.harold.fulcrum.data.route;

import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Objects;

public record OpenRoute(
        RouteId routeId,
        SubjectId subjectId,
        SessionId targetSessionId,
        InstanceId targetInstanceId,
        Instant requestedAt,
        Instant expiresAt) implements RouteCommand {
    public OpenRoute {
        routeId = Objects.requireNonNull(routeId, "routeId");
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        targetSessionId = Objects.requireNonNull(targetSessionId, "targetSessionId");
        targetInstanceId = Objects.requireNonNull(targetInstanceId, "targetInstanceId");
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(requestedAt)) {
            throw new IllegalArgumentException("expiresAt must be after requestedAt");
        }
    }
}
