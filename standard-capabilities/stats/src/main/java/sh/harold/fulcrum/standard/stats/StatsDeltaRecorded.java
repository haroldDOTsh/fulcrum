package sh.harold.fulcrum.standard.stats;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;

public record StatsDeltaRecorded(StatsLedgerEntry ledgerEntry, Revision revision) {
    public StatsDeltaRecorded {
        ledgerEntry = Objects.requireNonNull(ledgerEntry, "ledgerEntry");
        revision = Objects.requireNonNull(revision, "revision");
    }

    public String wireValue() {
        return ledgerEntry.wireValue();
    }
}
