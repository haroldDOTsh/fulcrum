package sh.harold.fulcrum.control.route;

import java.time.Instant;
import java.util.Objects;

public record TimeoutRouteAttempt(RouteAttemptId routeAttemptId, Instant timedOutAt) implements RouteAttemptCommand {
    public TimeoutRouteAttempt {
        routeAttemptId = Objects.requireNonNull(routeAttemptId, "routeAttemptId");
        timedOutAt = Objects.requireNonNull(timedOutAt, "timedOutAt");
    }
}
