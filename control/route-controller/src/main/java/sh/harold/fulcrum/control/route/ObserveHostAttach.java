package sh.harold.fulcrum.control.route;

import java.time.Instant;
import java.util.Objects;

public record ObserveHostAttach(RouteAttemptId routeAttemptId, Instant observedAt) implements RouteAttemptCommand {
    public ObserveHostAttach {
        routeAttemptId = Objects.requireNonNull(routeAttemptId, "routeAttemptId");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
    }
}
