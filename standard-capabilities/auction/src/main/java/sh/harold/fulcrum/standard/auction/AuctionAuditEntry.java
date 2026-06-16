package sh.harold.fulcrum.standard.auction;

import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public record AuctionAuditEntry(
        String auditEntryId,
        AuctionId auctionId,
        String action,
        Optional<SubjectId> actorSubjectId,
        long amountMinorUnits,
        String currencyKey,
        PrincipalId recordedBy,
        Instant recordedAt,
        Revision revision) {
    public AuctionAuditEntry {
        auditEntryId = requireNonBlank(auditEntryId, "auditEntryId");
        auctionId = Objects.requireNonNull(auctionId, "auctionId");
        action = requireNonBlank(action, "action");
        actorSubjectId = actorSubjectId == null ? Optional.empty() : actorSubjectId;
        currencyKey = requireNonBlank(currencyKey, "currencyKey").toLowerCase(Locale.ROOT);
        recordedBy = Objects.requireNonNull(recordedBy, "recordedBy");
        recordedAt = Objects.requireNonNull(recordedAt, "recordedAt");
        revision = Objects.requireNonNull(revision, "revision");
        if (amountMinorUnits < 0) {
            throw new IllegalArgumentException("amountMinorUnits must not be negative");
        }
    }

    public String wireValue() {
        return "auditEntryId=%s\nauctionId=%s\naction=%s\nactorSubjectId=%s\namountMinorUnits=%d\ncurrencyKey=%s\nrecordedBy=%s\nrecordedAt=%s\nrevision=%d"
                .formatted(
                        auditEntryId,
                        auctionId.value(),
                        action,
                        actorSubjectId.map(subject -> subject.value().toString()).orElse(""),
                        amountMinorUnits,
                        currencyKey,
                        recordedBy.value(),
                        recordedAt,
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
