package sh.harold.fulcrum.control.route;

public record RouteAttemptId(String value) {
    public RouteAttemptId {
        value = ControlRouteStrings.requireNonBlank(value, "routeAttemptId");
    }
}
