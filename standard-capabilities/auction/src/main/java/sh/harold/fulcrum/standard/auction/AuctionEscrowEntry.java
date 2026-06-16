package sh.harold.fulcrum.standard.auction;

import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

public record AuctionEscrowEntry(
        String entryId,
        AuctionId auctionId,
        SubjectId subjectId,
        AuctionEscrowAction action,
        long amountMinorUnits,
        String currencyKey,
        PrincipalId recordedBy,
        Instant recordedAt,
        String idempotencyKey,
        String commandId,
        Revision revision) {
    public AuctionEscrowEntry {
        entryId = requireNonBlank(entryId, "entryId");
        auctionId = Objects.requireNonNull(auctionId, "auctionId");
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        action = Objects.requireNonNull(action, "action");
        currencyKey = requireNonBlank(currencyKey, "currencyKey").toLowerCase(Locale.ROOT);
        recordedBy = Objects.requireNonNull(recordedBy, "recordedBy");
        recordedAt = Objects.requireNonNull(recordedAt, "recordedAt");
        idempotencyKey = requireNonBlank(idempotencyKey, "idempotencyKey");
        commandId = requireNonBlank(commandId, "commandId");
        revision = Objects.requireNonNull(revision, "revision");
        if (amountMinorUnits <= 0) {
            throw new IllegalArgumentException("amountMinorUnits must be positive");
        }
    }

    public AuctionEscrowAccountId accountId() {
        return new AuctionEscrowAccountId(auctionId, subjectId, currencyKey);
    }

    public long signedAmount() {
        return action == AuctionEscrowAction.HOLD ? amountMinorUnits : -amountMinorUnits;
    }

    public String wireValue() {
        return "entryId=%s\nauctionId=%s\nsubjectId=%s\naction=%s\namountMinorUnits=%d\ncurrencyKey=%s\nrecordedBy=%s\nrecordedAt=%s\nidempotencyKey=%s\ncommandId=%s\nrevision=%d"
                .formatted(
                        entryId,
                        auctionId.value(),
                        subjectId.value(),
                        action.name(),
                        amountMinorUnits,
                        currencyKey,
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
