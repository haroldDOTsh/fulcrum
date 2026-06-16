package sh.harold.fulcrum.standard.economy;

import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;

import java.time.Instant;
import java.util.Objects;

public record EconomyBalanceSnapshot(
        EconomyAccountId accountId,
        long balanceMinorUnits,
        String lastEntryId,
        PrincipalId updatedBy,
        Instant updatedAt) {
    public EconomyBalanceSnapshot {
        accountId = Objects.requireNonNull(accountId, "accountId");
        lastEntryId = requireNonBlank(lastEntryId, "lastEntryId");
        updatedBy = Objects.requireNonNull(updatedBy, "updatedBy");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (balanceMinorUnits < 0) {
            throw new IllegalArgumentException("balanceMinorUnits must not be negative");
        }
    }

    public String wireValue(Revision revision) {
        Objects.requireNonNull(revision, "revision");
        return "subjectId=%s\ncurrencyKey=%s\nbalanceMinorUnits=%d\nlastEntryId=%s\nupdatedBy=%s\nupdatedAt=%s\nrevision=%d"
                .formatted(
                        accountId.subjectId().value(),
                        accountId.currencyKey(),
                        balanceMinorUnits,
                        lastEntryId,
                        updatedBy.value(),
                        updatedAt,
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
