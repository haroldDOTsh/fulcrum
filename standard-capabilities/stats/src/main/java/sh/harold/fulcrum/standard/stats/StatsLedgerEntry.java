package sh.harold.fulcrum.standard.stats;

import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.ExperienceId;

import java.time.Instant;
import java.util.Objects;

public record StatsLedgerEntry(
        String entryId,
        StatsCounterId counterId,
        ExperienceId experienceId,
        long delta,
        long resultingTotal,
        PrincipalId recordedBy,
        Instant recordedAt,
        String idempotencyKey,
        String commandId,
        Revision revision) {
    public StatsLedgerEntry {
        entryId = requireNonBlank(entryId, "entryId");
        counterId = Objects.requireNonNull(counterId, "counterId");
        experienceId = Objects.requireNonNull(experienceId, "experienceId");
        recordedBy = Objects.requireNonNull(recordedBy, "recordedBy");
        recordedAt = Objects.requireNonNull(recordedAt, "recordedAt");
        idempotencyKey = requireNonBlank(idempotencyKey, "idempotencyKey");
        commandId = requireNonBlank(commandId, "commandId");
        revision = Objects.requireNonNull(revision, "revision");
        if (delta <= 0) {
            throw new IllegalArgumentException("delta must be positive");
        }
        if (resultingTotal < delta) {
            throw new IllegalArgumentException("resultingTotal must include delta");
        }
    }

    public StatsExperienceCounterId experienceCounterId() {
        return new StatsExperienceCounterId(counterId, experienceId);
    }

    public String wireValue() {
        return "entryId=%s\nsubjectId=%s\nexperienceId=%s\nstatKey=%s\ndelta=%d\nresultingTotal=%d\nrecordedBy=%s\nrecordedAt=%s\nidempotencyKey=%s\ncommandId=%s\nrevision=%d"
                .formatted(
                        entryId,
                        counterId.subjectId().value(),
                        experienceId.value(),
                        counterId.statKey(),
                        delta,
                        resultingTotal,
                        recordedBy.value(),
                        recordedAt,
                        idempotencyKey,
                        commandId,
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
