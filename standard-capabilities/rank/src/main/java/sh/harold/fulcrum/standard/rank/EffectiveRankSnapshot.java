package sh.harold.fulcrum.standard.rank;

import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Objects;

public record EffectiveRankSnapshot(
        SubjectId subjectId,
        String primaryRankKey,
        String permissions,
        PrincipalId updatedBy,
        Instant updatedAt) {
    public EffectiveRankSnapshot {
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        primaryRankKey = requireNonBlank(primaryRankKey, "primaryRankKey");
        permissions = requireNonBlank(permissions, "permissions");
        updatedBy = Objects.requireNonNull(updatedBy, "updatedBy");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    String wireValue(long revision) {
        return "subjectId=%s\nprimaryRankKey=%s\npermissions=%s\nupdatedBy=%s\nupdatedAt=%s\nrevision=%d"
                .formatted(subjectId.value(), escape(primaryRankKey), escape(permissions), updatedBy.value(), updatedAt, revision);
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
