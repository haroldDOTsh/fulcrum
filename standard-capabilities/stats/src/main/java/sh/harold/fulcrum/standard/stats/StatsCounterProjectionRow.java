package sh.harold.fulcrum.standard.stats;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;

public record StatsCounterProjectionRow(StatsCounterSnapshot snapshot, Revision revision) {
    public StatsCounterProjectionRow {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        revision = Objects.requireNonNull(revision, "revision");
    }

    public StatsCounterId counterId() {
        return snapshot.counterId();
    }

    public long total() {
        return snapshot.total();
    }
}
