package sh.harold.fulcrum.core.artifact;

import sh.harold.fulcrum.api.kernel.ArtifactId;

import java.util.Objects;
import java.util.Optional;

public record ArtifactSourceRequest(
        ArtifactId artifactId,
        String compatibility,
        ArtifactSourceKind sourceKind,
        String reference,
        Optional<String> expectedDigest,
        Optional<String> signatureReference,
        ArtifactSourcePolicy policy) {
    public ArtifactSourceRequest {
        artifactId = Objects.requireNonNull(artifactId, "artifactId");
        compatibility = ArtifactLayoutNames.requireNonBlank(compatibility, "compatibility");
        sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
        reference = ArtifactLayoutNames.requireNonBlank(reference, "reference");
        expectedDigest = expectedDigest == null ? Optional.empty() : expectedDigest
                .map(digest -> ArtifactLayoutNames.requireNonBlank(digest, "expectedDigest"));
        signatureReference = signatureReference == null ? Optional.empty() : signatureReference
                .map(signature -> ArtifactLayoutNames.requireNonBlank(signature, "signatureReference"));
        policy = Objects.requireNonNull(policy, "policy");
    }
}
