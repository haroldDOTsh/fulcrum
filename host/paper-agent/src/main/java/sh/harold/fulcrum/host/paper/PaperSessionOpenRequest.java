package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;

import java.time.Instant;
import java.util.Objects;

public record PaperSessionOpenRequest(
        SessionId sessionId,
        ExperienceId experienceId,
        SlotId slotId,
        InstanceId ownerInstanceId,
        String ownerToken,
        ResolvedManifestId resolvedManifestId,
        Instant openedAt,
        Instant leaseExpiresAt,
        TraceEnvelope traceEnvelope) {
    public PaperSessionOpenRequest {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        experienceId = Objects.requireNonNull(experienceId, "experienceId");
        slotId = Objects.requireNonNull(slotId, "slotId");
        ownerInstanceId = Objects.requireNonNull(ownerInstanceId, "ownerInstanceId");
        ownerToken = PaperArtifactNames.requireNonBlank(ownerToken, "ownerToken");
        resolvedManifestId = Objects.requireNonNull(resolvedManifestId, "resolvedManifestId");
        openedAt = Objects.requireNonNull(openedAt, "openedAt");
        leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        if (!leaseExpiresAt.isAfter(openedAt)) {
            throw new IllegalArgumentException("leaseExpiresAt must be after openedAt");
        }
    }
}
