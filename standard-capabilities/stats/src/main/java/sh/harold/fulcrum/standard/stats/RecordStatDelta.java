package sh.harold.fulcrum.standard.stats;

import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

public record RecordStatDelta(
        SubjectId subjectId,
        ExperienceId experienceId,
        String statKey,
        long delta,
        Instant occurredAt,
        long expectedRevision) implements CommandPayload {
    public RecordStatDelta {
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        experienceId = Objects.requireNonNull(experienceId, "experienceId");
        statKey = requireNonBlank(statKey, "statKey").toLowerCase(Locale.ROOT);
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        if (delta <= 0) {
            throw new IllegalArgumentException("delta must be positive");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
    }

    public StatsCounterId counterId() {
        return new StatsCounterId(subjectId, statKey);
    }

    public StatsExperienceCounterId experienceCounterId() {
        return new StatsExperienceCounterId(counterId(), experienceId);
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
