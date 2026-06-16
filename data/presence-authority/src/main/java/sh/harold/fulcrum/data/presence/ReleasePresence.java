package sh.harold.fulcrum.data.presence;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Objects;

public record ReleasePresence(
        SubjectId subjectId,
        PresenceOwnerToken ownerToken,
        long ownerEpoch,
        Instant releasedAt,
        PresenceReleaseReason reason) implements PresenceCommand {
    public ReleasePresence {
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        ownerToken = Objects.requireNonNull(ownerToken, "ownerToken");
        if (ownerEpoch <= 0) {
            throw new IllegalArgumentException("ownerEpoch must be positive");
        }
        releasedAt = Objects.requireNonNull(releasedAt, "releasedAt");
        reason = Objects.requireNonNull(reason, "reason");
    }
}
