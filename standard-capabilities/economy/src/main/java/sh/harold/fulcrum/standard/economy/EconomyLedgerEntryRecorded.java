package sh.harold.fulcrum.standard.economy;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;

public record EconomyLedgerEntryRecorded(EconomyLedgerEntry ledgerEntry, Revision revision) {
    public EconomyLedgerEntryRecorded {
        ledgerEntry = Objects.requireNonNull(ledgerEntry, "ledgerEntry");
        revision = Objects.requireNonNull(revision, "revision");
    }

    public String wireValue() {
        return ledgerEntry.wireValue();
    }
}
