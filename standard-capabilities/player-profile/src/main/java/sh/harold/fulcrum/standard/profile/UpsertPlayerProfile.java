package sh.harold.fulcrum.standard.profile;

import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Objects;

public record UpsertPlayerProfile(
        SubjectId subjectId,
        String displayName,
        Instant observedAt,
        long expectedRevision) implements CommandPayload {
    public UpsertPlayerProfile {
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        displayName = requireNonBlank(displayName, "displayName");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
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
