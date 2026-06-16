package sh.harold.fulcrum.control.route;

import java.time.Instant;
import java.util.Objects;

public record RetryRouteAttempt(
        RouteAttemptId routeAttemptId,
        Instant retryAt,
        Instant newDeadlineAt) implements RouteAttemptCommand {
    public RetryRouteAttempt {
        routeAttemptId = Objects.requireNonNull(routeAttemptId, "routeAttemptId");
        retryAt = Objects.requireNonNull(retryAt, "retryAt");
        newDeadlineAt = Objects.requireNonNull(newDeadlineAt, "newDeadlineAt");
    }
}
