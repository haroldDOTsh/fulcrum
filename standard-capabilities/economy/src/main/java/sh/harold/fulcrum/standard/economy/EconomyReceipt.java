package sh.harold.fulcrum.standard.economy;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;
import java.util.Optional;

public record EconomyReceipt(
        boolean accepted,
        Optional<EconomyLedgerEntry> ledgerEntry,
        Optional<EconomyBalanceSnapshot> snapshot,
        Optional<Revision> revision,
        long fencingEpoch,
        String idempotencyKey,
        String commandId,
        Optional<String> rejectionReason) {
    public EconomyReceipt {
        ledgerEntry = ledgerEntry == null ? Optional.empty() : ledgerEntry;
        snapshot = snapshot == null ? Optional.empty() : snapshot;
        revision = revision == null ? Optional.empty() : revision;
        idempotencyKey = idempotencyKey == null ? "" : idempotencyKey.trim();
        commandId = commandId == null ? "" : commandId.trim();
        rejectionReason = rejectionReason == null ? Optional.empty() : rejectionReason;
        if (accepted && (ledgerEntry.isEmpty() || snapshot.isEmpty() || revision.isEmpty())) {
            throw new IllegalArgumentException("accepted economy receipt requires ledger entry, snapshot, and revision");
        }
        if (!accepted && rejectionReason.isEmpty()) {
            throw new IllegalArgumentException("rejected economy receipt requires reason");
        }
    }

    public static EconomyReceipt accepted(
            EconomyLedgerEntry ledgerEntry,
            EconomyBalanceSnapshot snapshot,
            Revision revision,
            long fencingEpoch,
            String idempotencyKey,
            String commandId) {
        return new EconomyReceipt(
                true,
                Optional.of(Objects.requireNonNull(ledgerEntry, "ledgerEntry")),
                Optional.of(Objects.requireNonNull(snapshot, "snapshot")),
                Optional.of(Objects.requireNonNull(revision, "revision")),
                fencingEpoch,
                requireNonBlank(idempotencyKey, "idempotencyKey"),
                requireNonBlank(commandId, "commandId"),
                Optional.empty());
    }

    public static EconomyReceipt rejected(String reason) {
        return new EconomyReceipt(false, Optional.empty(), Optional.empty(), Optional.empty(), -1, "", "", Optional.of(requireNonBlank(reason, "reason")));
    }

    public String wireValue() {
        return accepted
                ? "accepted|%s|%s|%d|%d|%s|%s".formatted(
                        snapshot.orElseThrow().accountId().subjectId().value(),
                        snapshot.orElseThrow().accountId().currencyKey(),
                        snapshot.orElseThrow().balanceMinorUnits(),
                        revision.orElseThrow().value(),
                        idempotencyKey,
                        commandId)
                : "rejected|" + rejectionReason.orElseThrow();
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
