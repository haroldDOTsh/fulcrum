package sh.harold.fulcrum.standard.economy;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record EconomyState(Optional<EconomyBalanceSnapshot> current, List<EconomyLedgerEntry> ledgerEntries) {
    public EconomyState {
        current = current == null ? Optional.empty() : current;
        ledgerEntries = List.copyOf(Objects.requireNonNull(ledgerEntries, "ledgerEntries"));
    }

    public static EconomyState empty() {
        return new EconomyState(Optional.empty(), List.of());
    }

    public long balanceMinorUnits() {
        return current.map(EconomyBalanceSnapshot::balanceMinorUnits).orElse(0L);
    }

    public EconomyState append(EconomyLedgerEntry ledgerEntry, EconomyBalanceSnapshot snapshot) {
        Objects.requireNonNull(ledgerEntry, "ledgerEntry");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!ledgerEntry.accountId().equals(snapshot.accountId())) {
            throw new IllegalArgumentException("ledger entry and balance snapshot must address the same account");
        }
        return new EconomyState(Optional.of(snapshot), appendEntry(ledgerEntry));
    }

    public String wireValue(Revision revision) {
        Objects.requireNonNull(revision, "revision");
        return current.map(snapshot -> snapshot.wireValue(revision) + "\nledgerEntryCount=" + ledgerEntries.size())
                .orElse("empty=true\nrevision=" + revision.value() + "\nledgerEntryCount=0");
    }

    private List<EconomyLedgerEntry> appendEntry(EconomyLedgerEntry ledgerEntry) {
        java.util.ArrayList<EconomyLedgerEntry> next = new java.util.ArrayList<>(ledgerEntries);
        next.add(ledgerEntry);
        return List.copyOf(next);
    }
}
