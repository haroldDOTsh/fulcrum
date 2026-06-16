package sh.harold.fulcrum.standard.profile;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;
import java.util.Optional;

public record PlayerProfileState(Optional<PlayerProfileSnapshot> current) {
    public PlayerProfileState(PlayerProfileSnapshot current) {
        this(Optional.of(Objects.requireNonNull(current, "current")));
    }

    public PlayerProfileState {
        current = current == null ? Optional.empty() : current;
    }

    public static PlayerProfileState empty() {
        return new PlayerProfileState(Optional.empty());
    }

    public String wireValue(Revision revision) {
        Objects.requireNonNull(revision, "revision");
        return current.map(snapshot -> snapshot.wireValue(revision.value()))
                .orElse("empty=true\nrevision=" + revision.value());
    }
}
