package sh.harold.fulcrum.standard.stats;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record StatsState(Optional<StatsCounterSnapshot> current, List<StatsLedgerEntry> ledgerEntries) {
    public StatsState {
        current = current == null ? Optional.empty() : current;
        ledgerEntries = List.copyOf(Objects.requireNonNull(ledgerEntries, "ledgerEntries"));
    }

    public static StatsState empty() {
        return new StatsState(Optional.empty(), List.of());
    }

    public long total() {
        return current.map(StatsCounterSnapshot::total).orElse(0L);
    }

    public StatsState append(StatsLedgerEntry ledgerEntry, StatsCounterSnapshot snapshot) {
        Objects.requireNonNull(ledgerEntry, "ledgerEntry");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!ledgerEntry.counterId().equals(snapshot.counterId())) {
            throw new IllegalArgumentException("stats ledger entry and counter snapshot must address the same counter");
        }
        java.util.ArrayList<StatsLedgerEntry> next = new java.util.ArrayList<>(ledgerEntries);
        next.add(ledgerEntry);
        return new StatsState(Optional.of(snapshot), List.copyOf(next));
    }

    public String wireValue(Revision revision) {
        Objects.requireNonNull(revision, "revision");
        return current.map(snapshot -> snapshot.wireValue(revision) + "\nledgerEntryCount=" + ledgerEntries.size())
                .orElse("empty=true\nrevision=" + revision.value() + "\nledgerEntryCount=0");
    }
}
