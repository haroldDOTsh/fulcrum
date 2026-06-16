package sh.harold.fulcrum.standard.economy;

import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;

import java.time.Instant;
import java.util.Objects;

public record EconomyLedgerEntry(
        String entryId,
        EconomyAccountId accountId,
        long deltaMinorUnits,
        long resultingBalanceMinorUnits,
        String reason,
        PrincipalId recordedBy,
        Instant recordedAt,
        String idempotencyKey,
        String commandId,
        Revision revision) {
    public EconomyLedgerEntry {
        entryId = requireNonBlank(entryId, "entryId");
        accountId = Objects.requireNonNull(accountId, "accountId");
        reason = requireNonBlank(reason, "reason");
        recordedBy = Objects.requireNonNull(recordedBy, "recordedBy");
        recordedAt = Objects.requireNonNull(recordedAt, "recordedAt");
        idempotencyKey = requireNonBlank(idempotencyKey, "idempotencyKey");
        commandId = requireNonBlank(commandId, "commandId");
        revision = Objects.requireNonNull(revision, "revision");
        if (deltaMinorUnits == 0) {
            throw new IllegalArgumentException("deltaMinorUnits must not be zero");
        }
        if (resultingBalanceMinorUnits < 0) {
            throw new IllegalArgumentException("resultingBalanceMinorUnits must not be negative");
        }
    }

    public String wireValue() {
        return "entryId=%s\nsubjectId=%s\ncurrencyKey=%s\ndeltaMinorUnits=%d\nresultingBalanceMinorUnits=%d\nreason=%s\nrecordedBy=%s\nrecordedAt=%s\nidempotencyKey=%s\ncommandId=%s\nrevision=%d"
                .formatted(
                        entryId,
                        accountId.subjectId().value(),
                        accountId.currencyKey(),
                        deltaMinorUnits,
                        resultingBalanceMinorUnits,
                        reason,
                        recordedBy.value(),
                        recordedAt,
                        idempotencyKey,
                        commandId,
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
