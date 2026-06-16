package sh.harold.fulcrum.standard.economy;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;

public record EconomyBalanceProjectionRow(EconomyBalanceSnapshot snapshot, Revision revision) {
    public EconomyBalanceProjectionRow {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        revision = Objects.requireNonNull(revision, "revision");
    }

    public EconomyAccountId accountId() {
        return snapshot.accountId();
    }

    public long balanceMinorUnits() {
        return snapshot.balanceMinorUnits();
    }
}
