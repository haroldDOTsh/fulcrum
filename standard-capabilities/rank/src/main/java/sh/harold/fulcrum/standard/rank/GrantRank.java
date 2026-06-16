package sh.harold.fulcrum.standard.rank;

import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Objects;

public record GrantRank(
        SubjectId subjectId,
        String rankKey,
        Instant grantedAt,
        long expectedRevision) implements CommandPayload {
    public GrantRank {
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        rankKey = requireNonBlank(rankKey, "rankKey");
        grantedAt = Objects.requireNonNull(grantedAt, "grantedAt");
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
