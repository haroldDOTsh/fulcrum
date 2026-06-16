package sh.harold.fulcrum.standard.economy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record EconomyProjection(
        Map<EconomyAccountId, EconomyBalanceProjectionRow> balances,
        List<EconomyLedgerProjectionRow> ledgerEntries) {
    public EconomyProjection {
        balances = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(balances, "balances")));
        ledgerEntries = List.copyOf(Objects.requireNonNull(ledgerEntries, "ledgerEntries"));
    }

    public static EconomyProjection empty() {
        return new EconomyProjection(Map.of(), List.of());
    }

    public static EconomyProjection rebuild(List<EconomyLedgerEntryRecorded> events) {
        Objects.requireNonNull(events, "events");
        LinkedHashMap<EconomyAccountId, EconomyBalanceProjectionRow> balances = new LinkedHashMap<>();
        ArrayList<EconomyLedgerProjectionRow> ledgerRows = new ArrayList<>();
        for (EconomyLedgerEntryRecorded event : events) {
            EconomyLedgerEntry entry = Objects.requireNonNull(event, "event").ledgerEntry();
            EconomyBalanceProjectionRow current = balances.get(entry.accountId());
            if (current != null && entry.revision().value() <= current.revision().value()) {
                throw new IllegalArgumentException("economy projection replay requires increasing revisions per account");
            }
            long previousBalance = current == null ? 0L : current.balanceMinorUnits();
            long expectedBalance = Math.addExact(previousBalance, entry.deltaMinorUnits());
            if (expectedBalance != entry.resultingBalanceMinorUnits()) {
                throw new IllegalArgumentException("economy ledger entry does not match running balance");
            }
            EconomyBalanceSnapshot snapshot = new EconomyBalanceSnapshot(
                    entry.accountId(),
                    entry.resultingBalanceMinorUnits(),
                    entry.entryId(),
                    entry.recordedBy(),
                    entry.recordedAt());
            balances.put(entry.accountId(), new EconomyBalanceProjectionRow(snapshot, entry.revision()));
            ledgerRows.add(new EconomyLedgerProjectionRow(entry));
        }
        return new EconomyProjection(balances, ledgerRows);
    }

    public Optional<EconomyBalanceProjectionRow> balance(EconomyAccountId accountId) {
        return Optional.ofNullable(balances.get(Objects.requireNonNull(accountId, "accountId")));
    }

    public List<EconomyLedgerProjectionRow> ledgerEntriesFor(EconomyAccountId accountId) {
        Objects.requireNonNull(accountId, "accountId");
        return ledgerEntries.stream()
                .filter(row -> row.accountId().equals(accountId))
                .toList();
    }
}
