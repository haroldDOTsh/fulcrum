package sh.harold.fulcrum.control.route;

import java.time.Instant;
import java.util.Objects;

public record AcknowledgeRouteAttempt(RouteAttemptId routeAttemptId, Instant acknowledgedAt) implements RouteAttemptCommand {
    public AcknowledgeRouteAttempt {
        routeAttemptId = Objects.requireNonNull(routeAttemptId, "routeAttemptId");
        acknowledgedAt = Objects.requireNonNull(acknowledgedAt, "acknowledgedAt");
    }
}
