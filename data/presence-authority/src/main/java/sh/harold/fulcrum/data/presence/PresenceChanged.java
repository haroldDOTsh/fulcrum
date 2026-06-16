package sh.harold.fulcrum.data.presence;

import sh.harold.fulcrum.api.contract.EventPayload;
import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;

public record PresenceChanged(
        PresenceChangeKind kind,
        PresenceSnapshot snapshot,
        Revision revision) implements EventPayload {
    public PresenceChanged {
        kind = Objects.requireNonNull(kind, "kind");
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        revision = Objects.requireNonNull(revision, "revision");
    }

    static PresenceChanged from(PresenceChangeKind kind, PresenceSnapshot snapshot, Revision revision) {
        return new PresenceChanged(kind, snapshot, revision);
    }

    String wireValue() {
        return "change=" + kind.name()
                + "\n" + snapshot.wireValue()
                + "\nrevision=" + revision.value();
    }
}
