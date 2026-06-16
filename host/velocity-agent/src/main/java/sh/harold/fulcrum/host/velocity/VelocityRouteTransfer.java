package sh.harold.fulcrum.host.velocity;

import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Objects;

public record VelocityRouteTransfer(
        RouteId routeId,
        SubjectId subjectId,
        SessionId targetSessionId,
        InstanceId targetInstanceId,
        Instant acknowledgedAt) {
    public VelocityRouteTransfer {
        routeId = Objects.requireNonNull(routeId, "routeId");
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        targetSessionId = Objects.requireNonNull(targetSessionId, "targetSessionId");
        targetInstanceId = Objects.requireNonNull(targetInstanceId, "targetInstanceId");
        acknowledgedAt = Objects.requireNonNull(acknowledgedAt, "acknowledgedAt");
    }
}
