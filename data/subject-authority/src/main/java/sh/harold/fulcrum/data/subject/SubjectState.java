package sh.harold.fulcrum.data.subject;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;
import java.util.Optional;

public record SubjectState(Optional<SubjectSnapshot> current) {
    public SubjectState {
        current = current == null ? Optional.empty() : current;
    }

    public SubjectState(SubjectSnapshot snapshot) {
        this(Optional.of(Objects.requireNonNull(snapshot, "snapshot")));
    }

    public static SubjectState empty() {
        return new SubjectState(Optional.empty());
    }

    String wireValue(Revision revision) {
        return current.map(snapshot -> snapshot.wireValue() + "\nrevision=" + revision.value())
                .orElse("revision=" + revision.value());
    }
}
