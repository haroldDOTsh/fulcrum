package sh.harold.fulcrum.standard.economy;

import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

public record PostLedgerEntry(
        SubjectId subjectId,
        String currencyKey,
        long deltaMinorUnits,
        String reason,
        Instant occurredAt,
        long expectedRevision) implements CommandPayload {
    public PostLedgerEntry {
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        currencyKey = requireNonBlank(currencyKey, "currencyKey").toLowerCase(Locale.ROOT);
        reason = requireNonBlank(reason, "reason");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        if (deltaMinorUnits == 0) {
            throw new IllegalArgumentException("deltaMinorUnits must not be zero");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
    }

    public EconomyAccountId accountId() {
        return new EconomyAccountId(subjectId, currencyKey);
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
