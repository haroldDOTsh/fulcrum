package sh.harold.fulcrum.core.artifact;

import sh.harold.fulcrum.core.manifest.ArtifactPin;

import java.nio.file.Path;
import java.util.Objects;

public record VerifiedArtifact(
        ArtifactPin artifactPin,
        ArtifactSourceKind sourceKind,
        String sourceReference,
        Path cachedPath,
        ArtifactVerificationReceipt verificationReceipt) {
    public VerifiedArtifact {
        artifactPin = Objects.requireNonNull(artifactPin, "artifactPin");
        sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
        sourceReference = ArtifactLayoutNames.requireNonBlank(sourceReference, "sourceReference");
        cachedPath = Objects.requireNonNull(cachedPath, "cachedPath").toAbsolutePath().normalize();
        verificationReceipt = Objects.requireNonNull(verificationReceipt, "verificationReceipt");
        if (verificationReceipt.status() != ArtifactVerificationStatus.VERIFIED) {
            throw new IllegalArgumentException("verified artifact requires a verified receipt");
        }
    }
}
