package sh.harold.fulcrum.standard.economy;

import java.util.Objects;

public record EconomyLedgerProjectionRow(EconomyLedgerEntry ledgerEntry) {
    public EconomyLedgerProjectionRow {
        ledgerEntry = Objects.requireNonNull(ledgerEntry, "ledgerEntry");
    }

    public String entryId() {
        return ledgerEntry.entryId();
    }

    public EconomyAccountId accountId() {
        return ledgerEntry.accountId();
    }
}
