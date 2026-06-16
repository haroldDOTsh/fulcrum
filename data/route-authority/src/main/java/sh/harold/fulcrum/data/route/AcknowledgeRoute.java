package sh.harold.fulcrum.data.route;

import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Objects;

public record AcknowledgeRoute(
        RouteId routeId,
        SubjectId subjectId,
        SessionId targetSessionId,
        InstanceId targetInstanceId,
        Instant acknowledgedAt) implements RouteCommand {
    public AcknowledgeRoute {
        routeId = Objects.requireNonNull(routeId, "routeId");
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        targetSessionId = Objects.requireNonNull(targetSessionId, "targetSessionId");
        targetInstanceId = Objects.requireNonNull(targetInstanceId, "targetInstanceId");
        acknowledgedAt = Objects.requireNonNull(acknowledgedAt, "acknowledgedAt");
    }
}
