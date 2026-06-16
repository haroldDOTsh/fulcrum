package sh.harold.fulcrum.data.session;

import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;

import java.time.Instant;
import java.util.Objects;

public record OpenSession(
        SessionId sessionId,
        ExperienceId experienceId,
        SlotId slotId,
        InstanceId ownerInstanceId,
        SessionOwnerToken ownerToken,
        ResolvedManifestId resolvedManifestId,
        Instant openedAt,
        Instant leaseExpiresAt) implements SessionCommand {
    public OpenSession {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        experienceId = Objects.requireNonNull(experienceId, "experienceId");
        slotId = Objects.requireNonNull(slotId, "slotId");
        ownerInstanceId = Objects.requireNonNull(ownerInstanceId, "ownerInstanceId");
        ownerToken = Objects.requireNonNull(ownerToken, "ownerToken");
        resolvedManifestId = Objects.requireNonNull(resolvedManifestId, "resolvedManifestId");
        openedAt = Objects.requireNonNull(openedAt, "openedAt");
        leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        if (!leaseExpiresAt.isAfter(openedAt)) {
            throw new IllegalArgumentException("leaseExpiresAt must be after openedAt");
        }
    }
}
