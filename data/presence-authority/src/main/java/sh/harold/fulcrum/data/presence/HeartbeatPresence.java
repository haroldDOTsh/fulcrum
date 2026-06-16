package sh.harold.fulcrum.data.presence;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Objects;

public record HeartbeatPresence(
        SubjectId subjectId,
        PresenceOwnerToken ownerToken,
        long ownerEpoch,
        Instant observedAt,
        Instant expiresAt) implements PresenceCommand {
    public HeartbeatPresence {
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        ownerToken = Objects.requireNonNull(ownerToken, "ownerToken");
        if (ownerEpoch <= 0) {
            throw new IllegalArgumentException("ownerEpoch must be positive");
        }
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(observedAt)) {
            throw new IllegalArgumentException("expiresAt must be after observedAt");
        }
    }
}
