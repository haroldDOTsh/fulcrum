package sh.harold.fulcrum.data.route;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;
import java.util.Optional;

public record RouteState(Optional<RouteSnapshot> current) {
    public RouteState {
        current = current == null ? Optional.empty() : current;
    }

    public RouteState(RouteSnapshot snapshot) {
        this(Optional.of(Objects.requireNonNull(snapshot, "snapshot")));
    }

    public static RouteState empty() {
        return new RouteState(Optional.empty());
    }

    String wireValue(Revision revision) {
        return current.map(snapshot -> snapshot.wireValue() + "\nrevision=" + revision.value())
                .orElse("revision=" + revision.value());
    }
}
