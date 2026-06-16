package sh.harold.fulcrum.standard.profile;

import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Objects;

public record PlayerProfileSnapshot(
        SubjectId subjectId,
        String displayName,
        PrincipalId updatedBy,
        Instant observedAt) {
    public PlayerProfileSnapshot {
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        displayName = requireNonBlank(displayName, "displayName");
        updatedBy = Objects.requireNonNull(updatedBy, "updatedBy");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
    }

    String wireValue(long revision) {
        return "subjectId=%s\ndisplayName=%s\nupdatedBy=%s\nobservedAt=%s\nrevision=%d"
                .formatted(subjectId.value(), escape(displayName), updatedBy.value(), observedAt, revision);
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
