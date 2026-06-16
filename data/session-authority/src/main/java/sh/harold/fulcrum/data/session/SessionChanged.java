package sh.harold.fulcrum.data.session;

import sh.harold.fulcrum.api.contract.EventPayload;
import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;

public record SessionChanged(
        SessionChangeKind kind,
        SessionSnapshot snapshot,
        Revision revision) implements EventPayload {
    public SessionChanged {
        kind = Objects.requireNonNull(kind, "kind");
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        revision = Objects.requireNonNull(revision, "revision");
    }

    static SessionChanged from(SessionChangeKind kind, SessionSnapshot snapshot, Revision revision) {
        return new SessionChanged(kind, snapshot, revision);
    }

    String wireValue() {
        return "change=" + kind.name()
                + "\n" + snapshot.wireValue()
                + "\nrevision=" + revision.value();
    }
}
