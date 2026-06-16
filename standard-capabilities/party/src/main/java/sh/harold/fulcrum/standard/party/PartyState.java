package sh.harold.fulcrum.standard.party;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;
import java.util.Optional;

public record PartyState(Optional<PartyRosterSnapshot> current) {
    public PartyState(PartyRosterSnapshot current) {
        this(Optional.of(Objects.requireNonNull(current, "current")));
    }

    public PartyState {
        current = current == null ? Optional.empty() : current;
    }

    public static PartyState empty() {
        return new PartyState(Optional.empty());
    }

    public String wireValue(Revision revision) {
        Objects.requireNonNull(revision, "revision");
        return current.map(snapshot -> snapshot.wireValue(revision))
                .orElse("empty=true\nrevision=" + revision.value());
    }
}
