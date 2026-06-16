package sh.harold.fulcrum.api.kernel;

public record RouteId(String value) {
    public RouteId {
        value = Ids.requireNonBlank(value, "routeId");
    }
}
