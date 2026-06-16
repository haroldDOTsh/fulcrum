package sh.harold.fulcrum.standard.punishment;

import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Objects;

public record ActivePunishmentSnapshot(
        SubjectId subjectId,
        String punishmentId,
        String reason,
        PrincipalId issuedBy,
        Instant issuedAt,
        Instant expiresAt) {
    public ActivePunishmentSnapshot {
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        punishmentId = requireNonBlank(punishmentId, "punishmentId");
        reason = requireNonBlank(reason, "reason");
        issuedBy = Objects.requireNonNull(issuedBy, "issuedBy");
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
    }

    public boolean activeAt(Instant instant) {
        return expiresAt.isAfter(Objects.requireNonNull(instant, "instant"));
    }

    String wireValue(long revision) {
        return "subjectId=%s\npunishmentId=%s\nreason=%s\nissuedBy=%s\nissuedAt=%s\nexpiresAt=%s\nrevision=%d"
                .formatted(
                        subjectId.value(),
                        escape(punishmentId),
                        escape(reason),
                        issuedBy.value(),
                        issuedAt,
                        expiresAt,
                        revision);
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("=", "\\=");
    }
}
