package sh.harold.fulcrum.standard.punishment;

import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Objects;

public record IssuePunishment(
        SubjectId subjectId,
        String punishmentId,
        String reason,
        Instant issuedAt,
        Instant expiresAt,
        long expectedRevision) implements CommandPayload {
    public IssuePunishment {
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        punishmentId = requireNonBlank(punishmentId, "punishmentId");
        reason = requireNonBlank(reason, "reason");
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must be non-negative");
        }
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
