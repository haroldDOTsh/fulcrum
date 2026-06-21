package sh.harold.fulcrum.core.artifact;

import sh.harold.fulcrum.core.manifest.ArtifactPin;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ArtifactVerificationReceipt(
        ArtifactVerificationStatus status,
        Optional<ArtifactPin> artifactPin,
        ArtifactSourceKind sourceKind,
        String sourceReference,
        Optional<String> digest,
        Optional<Path> cachedPath,
        List<ArtifactVerificationStep> steps,
        Optional<String> signatureEvidence,
        Optional<String> refusalReason) {
    public ArtifactVerificationReceipt {
        status = Objects.requireNonNull(status, "status");
        artifactPin = artifactPin == null ? Optional.empty() : artifactPin;
        sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
        sourceReference = ArtifactLayoutNames.requireNonBlank(sourceReference, "sourceReference");
        digest = digest == null ? Optional.empty() : digest
                .map(value -> ArtifactLayoutNames.requireNonBlank(value, "digest"));
        cachedPath = cachedPath == null ? Optional.empty() : cachedPath.map(path -> path.toAbsolutePath().normalize());
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        signatureEvidence = signatureEvidence == null ? Optional.empty() : signatureEvidence
                .map(value -> ArtifactLayoutNames.requireNonBlank(value, "signatureEvidence"));
        refusalReason = refusalReason == null ? Optional.empty() : refusalReason
                .map(value -> ArtifactLayoutNames.requireNonBlank(value, "refusalReason"));
        if (status == ArtifactVerificationStatus.VERIFIED && artifactPin.isEmpty()) {
            throw new IllegalArgumentException("verified artifact receipt must include an artifact pin");
        }
        if (status == ArtifactVerificationStatus.VERIFIED && refusalReason.isPresent()) {
            throw new IllegalArgumentException("verified artifact receipt must not include a refusal reason");
        }
        if (status == ArtifactVerificationStatus.REFUSED && refusalReason.isEmpty()) {
            throw new IllegalArgumentException("refused artifact receipt must include a refusal reason");
        }
    }
}
