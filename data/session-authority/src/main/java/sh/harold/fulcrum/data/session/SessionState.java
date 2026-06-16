package sh.harold.fulcrum.data.session;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;
import java.util.Optional;

public record SessionState(Optional<SessionSnapshot> current) {
    public SessionState {
        current = current == null ? Optional.empty() : current;
    }

    public SessionState(SessionSnapshot snapshot) {
        this(Optional.of(Objects.requireNonNull(snapshot, "snapshot")));
    }

    public static SessionState empty() {
        return new SessionState(Optional.empty());
    }

    String wireValue(Revision revision) {
        return current.map(snapshot -> snapshot.wireValue() + "\nrevision=" + revision.value())
                .orElse("revision=" + revision.value());
    }
}
