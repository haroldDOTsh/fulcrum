package sh.harold.fulcrum.data.route.contract;

import sh.harold.fulcrum.api.kernel.RouteId;

import java.time.Instant;
import java.util.Objects;

public record TimeoutRoute(RouteId routeId, Instant timedOutAt) implements RouteCommand {
    public TimeoutRoute {
        routeId = Objects.requireNonNull(routeId, "routeId");
        timedOutAt = Objects.requireNonNull(timedOutAt, "timedOutAt");
    }
}
