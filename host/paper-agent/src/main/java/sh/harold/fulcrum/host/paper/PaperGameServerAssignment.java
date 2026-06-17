package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;
import sh.harold.fulcrum.core.manifest.ArtifactPin;
import sh.harold.fulcrum.core.manifest.ResolvedManifest;

import java.time.Duration;
import java.util.Objects;

public record PaperGameServerAssignment(
        ExperienceId experienceId,
        SessionId sessionId,
        SlotId slotId,
        ResolvedManifest resolvedManifest,
        ArtifactPin worldArtifact,
        String sessionOwnerToken,
        Duration sessionLease) {
    public PaperGameServerAssignment {
        experienceId = Objects.requireNonNull(experienceId, "experienceId");
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        slotId = Objects.requireNonNull(slotId, "slotId");
        resolvedManifest = Objects.requireNonNull(resolvedManifest, "resolvedManifest");
        worldArtifact = Objects.requireNonNull(worldArtifact, "worldArtifact");
        sessionOwnerToken = PaperArtifactNames.requireNonBlank(sessionOwnerToken, "sessionOwnerToken");
        sessionLease = Objects.requireNonNull(sessionLease, "sessionLease");
        if (sessionLease.isNegative() || sessionLease.isZero()) {
            throw new IllegalArgumentException("sessionLease must be positive");
        }
        if (!resolvedManifest.contentArtifacts().contains(worldArtifact)) {
            throw new IllegalArgumentException("worldArtifact must be pinned by the ResolvedManifest");
        }
    }

    public ResolvedManifestId resolvedManifestId() {
        return resolvedManifest.resolvedManifestId();
    }

    public PaperGameServerAssignment withAllocationMetadata(AgonesGameServerSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        String allocatedManifestId = snapshot.annotation(AgonesGameServerSnapshot.RESOLVED_MANIFEST_ID_ANNOTATION)
                .orElse(resolvedManifestId().value());
        if (!resolvedManifestId().value().equals(allocatedManifestId)) {
            throw new IllegalStateException(
                    "Agones allocation resolvedManifestId does not match prepared manifest: " + allocatedManifestId);
        }
        SessionId allocatedSessionId = snapshot.annotation(AgonesGameServerSnapshot.SESSION_ID_ANNOTATION)
                .map(SessionId::new)
                .orElse(sessionId);
        SlotId allocatedSlotId = snapshot.annotation(AgonesGameServerSnapshot.SLOT_ID_ANNOTATION)
                .map(SlotId::new)
                .orElse(slotId);
        return new PaperGameServerAssignment(
                experienceId,
                allocatedSessionId,
                allocatedSlotId,
                resolvedManifest,
                worldArtifact,
                sessionOwnerToken,
                sessionLease);
    }
}
