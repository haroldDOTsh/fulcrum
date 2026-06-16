package sh.harold.fulcrum.data.route;

import sh.harold.fulcrum.api.contract.EventPayload;
import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;

public record RouteChanged(
        RouteChangeKind kind,
        RouteSnapshot snapshot,
        Revision revision) implements EventPayload {
    public RouteChanged {
        kind = Objects.requireNonNull(kind, "kind");
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        revision = Objects.requireNonNull(revision, "revision");
    }

    static RouteChanged from(RouteChangeKind kind, RouteSnapshot snapshot, Revision revision) {
        return new RouteChanged(kind, snapshot, revision);
    }

    String wireValue() {
        return "change=" + kind.name()
                + "\n" + snapshot.wireValue()
                + "\nrevision=" + revision.value();
    }
}
