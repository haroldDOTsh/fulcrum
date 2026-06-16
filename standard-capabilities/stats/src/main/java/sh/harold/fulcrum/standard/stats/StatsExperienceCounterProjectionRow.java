package sh.harold.fulcrum.standard.stats;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;

public record StatsExperienceCounterProjectionRow(
        StatsExperienceCounterId experienceCounterId,
        long total,
        Revision revision) {
    public StatsExperienceCounterProjectionRow {
        experienceCounterId = Objects.requireNonNull(experienceCounterId, "experienceCounterId");
        revision = Objects.requireNonNull(revision, "revision");
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative");
        }
    }
}
