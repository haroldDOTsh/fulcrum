package sh.harold.fulcrum.standard.rank;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;
import java.util.Optional;

public record RankState(Optional<EffectiveRankSnapshot> current) {
    public RankState(EffectiveRankSnapshot current) {
        this(Optional.of(Objects.requireNonNull(current, "current")));
    }

    public RankState {
        current = current == null ? Optional.empty() : current;
    }

    public static RankState empty() {
        return new RankState(Optional.empty());
    }

    public String wireValue(Revision revision) {
        Objects.requireNonNull(revision, "revision");
        return current.map(snapshot -> snapshot.wireValue(revision.value()))
                .orElse("empty=true\nrevision=" + revision.value());
    }
}
