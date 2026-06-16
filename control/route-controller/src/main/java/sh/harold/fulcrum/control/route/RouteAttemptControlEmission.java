package sh.harold.fulcrum.control.route;

public record RouteAttemptControlEmission(
        RouteAttemptControlEmissionKind kind,
        String key,
        String value) {
    public RouteAttemptControlEmission {
        kind = java.util.Objects.requireNonNull(kind, "kind");
        key = ControlRouteStrings.requireNonBlank(key, "key");
        value = ControlRouteStrings.requireNonBlank(value, "value");
    }
}
