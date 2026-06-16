package sh.harold.fulcrum.standard.stats;

import sh.harold.fulcrum.api.kernel.ExperienceId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record StatsProjection(
        Map<StatsCounterId, StatsCounterProjectionRow> counters,
        Map<StatsExperienceCounterId, StatsExperienceCounterProjectionRow> experienceCounters,
        List<StatsLedgerProjectionRow> ledgerEntries) {
    public StatsProjection {
        counters = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(counters, "counters")));
        experienceCounters = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(experienceCounters, "experienceCounters")));
        ledgerEntries = List.copyOf(Objects.requireNonNull(ledgerEntries, "ledgerEntries"));
    }

    public static StatsProjection empty() {
        return new StatsProjection(Map.of(), Map.of(), List.of());
    }

    public static StatsProjection rebuild(List<StatsDeltaRecorded> events) {
        Objects.requireNonNull(events, "events");
        LinkedHashMap<StatsCounterId, StatsCounterProjectionRow> counters = new LinkedHashMap<>();
        LinkedHashMap<StatsExperienceCounterId, StatsExperienceCounterProjectionRow> experienceCounters = new LinkedHashMap<>();
        ArrayList<StatsLedgerProjectionRow> ledgerRows = new ArrayList<>();
        for (StatsDeltaRecorded event : events) {
            StatsLedgerEntry entry = Objects.requireNonNull(event, "event").ledgerEntry();
            StatsCounterProjectionRow current = counters.get(entry.counterId());
            if (current != null && entry.revision().value() <= current.revision().value()) {
                throw new IllegalArgumentException("stats projection replay requires increasing revisions per counter");
            }
            long previousTotal = current == null ? 0L : current.total();
            long expectedTotal = Math.addExact(previousTotal, entry.delta());
            if (expectedTotal != entry.resultingTotal()) {
                throw new IllegalArgumentException("stats ledger entry does not match running total");
            }
            StatsCounterSnapshot snapshot = new StatsCounterSnapshot(
                    entry.counterId(),
                    entry.resultingTotal(),
                    entry.entryId(),
                    entry.recordedBy(),
                    entry.recordedAt());
            counters.put(entry.counterId(), new StatsCounterProjectionRow(snapshot, entry.revision()));

            StatsExperienceCounterId experienceCounterId = entry.experienceCounterId();
            StatsExperienceCounterProjectionRow currentExperience = experienceCounters.get(experienceCounterId);
            long experienceTotal = (currentExperience == null ? 0L : currentExperience.total()) + entry.delta();
            experienceCounters.put(experienceCounterId, new StatsExperienceCounterProjectionRow(
                    experienceCounterId,
                    experienceTotal,
                    entry.revision()));
            ledgerRows.add(new StatsLedgerProjectionRow(entry));
        }
        return new StatsProjection(counters, experienceCounters, ledgerRows);
    }

    public Optional<StatsCounterProjectionRow> counter(StatsCounterId counterId) {
        return Optional.ofNullable(counters.get(Objects.requireNonNull(counterId, "counterId")));
    }

    public Optional<StatsExperienceCounterProjectionRow> experienceCounter(
            StatsCounterId counterId,
            ExperienceId experienceId) {
        return Optional.ofNullable(experienceCounters.get(new StatsExperienceCounterId(counterId, experienceId)));
    }
}
