package sh.harold.fulcrum.data.subject;

import sh.harold.fulcrum.api.contract.EventPayload;
import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;

public record SubjectChanged(
        SubjectChangeKind kind,
        SubjectSnapshot snapshot,
        Revision revision) implements EventPayload {
    public SubjectChanged {
        kind = Objects.requireNonNull(kind, "kind");
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        revision = Objects.requireNonNull(revision, "revision");
    }

    static SubjectChanged from(SubjectChangeKind kind, SubjectSnapshot snapshot, Revision revision) {
        return new SubjectChanged(kind, snapshot, revision);
    }

    String wireValue() {
        return "change=" + kind.name()
                + "\n" + snapshot.wireValue()
                + "\nrevision=" + revision.value();
    }
}
