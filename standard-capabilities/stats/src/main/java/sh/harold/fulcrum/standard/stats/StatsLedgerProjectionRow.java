package sh.harold.fulcrum.standard.stats;

import java.util.Objects;

public record StatsLedgerProjectionRow(StatsLedgerEntry ledgerEntry) {
    public StatsLedgerProjectionRow {
        ledgerEntry = Objects.requireNonNull(ledgerEntry, "ledgerEntry");
    }

    public String entryId() {
        return ledgerEntry.entryId();
    }

    public StatsCounterId counterId() {
        return ledgerEntry.counterId();
    }
}
