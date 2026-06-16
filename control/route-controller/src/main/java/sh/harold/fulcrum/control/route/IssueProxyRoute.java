package sh.harold.fulcrum.control.route;

import java.time.Instant;
import java.util.Objects;

public record IssueProxyRoute(RouteAttemptId routeAttemptId, Instant issuedAt) implements RouteAttemptCommand {
    public IssueProxyRoute {
        routeAttemptId = Objects.requireNonNull(routeAttemptId, "routeAttemptId");
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
    }
}
