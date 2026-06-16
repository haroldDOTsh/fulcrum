package sh.harold.fulcrum.standard.rank;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.standard.contracts.RankContracts;

import java.util.Objects;

public record EffectiveRankProjectionRow(EffectiveRankSnapshot snapshot, Revision revision) {
    public EffectiveRankProjectionRow {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        revision = Objects.requireNonNull(revision, "revision");
    }

    public SubjectId subjectId() {
        return snapshot.subjectId();
    }

    public String key() {
        return RankContracts.EFFECTIVE_PROJECTION + ":" + subjectId().value();
    }

    public String wireValue() {
        return snapshot.wireValue(revision.value());
    }
}
