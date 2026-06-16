package sh.harold.fulcrum.data.presence;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;
import java.util.Optional;

public record PresenceState(Optional<PresenceSnapshot> current) {
    public PresenceState {
        current = current == null ? Optional.empty() : current;
    }

    public PresenceState(PresenceSnapshot current) {
        this(Optional.of(Objects.requireNonNull(current, "current")));
    }

    static PresenceState empty() {
        return new PresenceState(Optional.empty());
    }

    String wireValue(Revision revision) {
        return current.map(value -> value.wireValue()
                        + "\nrevision=" + revision.value())
                .orElse("revision=" + revision.value());
    }
}
