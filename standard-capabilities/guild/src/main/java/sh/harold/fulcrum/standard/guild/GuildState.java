package sh.harold.fulcrum.standard.guild;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;
import java.util.Optional;

public record GuildState(Optional<GuildRosterSnapshot> current) {
    public GuildState(GuildRosterSnapshot current) {
        this(Optional.of(Objects.requireNonNull(current, "current")));
    }

    public GuildState {
        current = current == null ? Optional.empty() : current;
    }

    public static GuildState empty() {
        return new GuildState(Optional.empty());
    }

    public String wireValue(Revision revision) {
        Objects.requireNonNull(revision, "revision");
        return current.map(snapshot -> snapshot.wireValue(revision))
                .orElse("empty=true\nrevision=" + revision.value());
    }
}
