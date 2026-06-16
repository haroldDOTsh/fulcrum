package sh.harold.fulcrum.data.presence;

import sh.harold.fulcrum.api.contract.EventPayload;
import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;

public record PresenceClaimed(
        PresenceSnapshot snapshot,
        Revision revision) implements EventPayload {
    public PresenceClaimed {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        revision = Objects.requireNonNull(revision, "revision");
    }

    static PresenceClaimed from(PresenceSnapshot snapshot, Revision revision) {
        return new PresenceClaimed(snapshot, revision);
    }

    String wireValue() {
        return snapshot.wireValue()
                + "\nrevision=" + revision.value();
    }
}
