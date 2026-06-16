package sh.harold.fulcrum.standard.rank;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;

public record RankGranted(EffectiveRankSnapshot snapshot, Revision revision) {
    public RankGranted {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        revision = Objects.requireNonNull(revision, "revision");
    }

    public String wireValue() {
        return "rank-granted\n" + snapshot.wireValue(revision.value());
    }
}
