package sh.harold.fulcrum.standard.stats;

import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;

import java.time.Instant;
import java.util.Objects;

public record StatsCounterSnapshot(
        StatsCounterId counterId,
        long total,
        String lastEntryId,
        PrincipalId updatedBy,
        Instant updatedAt) {
    public StatsCounterSnapshot {
        counterId = Objects.requireNonNull(counterId, "counterId");
        lastEntryId = requireNonBlank(lastEntryId, "lastEntryId");
        updatedBy = Objects.requireNonNull(updatedBy, "updatedBy");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative");
        }
    }

    public String wireValue(Revision revision) {
        Objects.requireNonNull(revision, "revision");
        return "subjectId=%s\nstatKey=%s\ntotal=%d\nlastEntryId=%s\nupdatedBy=%s\nupdatedAt=%s\nrevision=%d"
                .formatted(
                        counterId.subjectId().value(),
                        counterId.statKey(),
                        total,
                        lastEntryId,
                        updatedBy.value(),
                        updatedAt,
                        revision.value());
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
