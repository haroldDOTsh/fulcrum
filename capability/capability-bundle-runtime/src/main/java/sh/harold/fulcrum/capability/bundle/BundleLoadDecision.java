package sh.harold.fulcrum.capability.bundle;

import sh.harold.fulcrum.core.artifact.ArtifactObjectAddress;
import sh.harold.fulcrum.core.manifest.ArtifactPin;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record BundleLoadDecision(
        BundleLoadStatus status,
        ArtifactPin artifactPin,
        ArtifactObjectAddress objectAddress,
        Optional<Path> cachedPath,
        List<BundleLoadStep> steps,
        Optional<String> refusalReason) {
    public BundleLoadDecision {
        status = Objects.requireNonNull(status, "status");
        artifactPin = Objects.requireNonNull(artifactPin, "artifactPin");
        objectAddress = Objects.requireNonNull(objectAddress, "objectAddress");
        cachedPath = cachedPath == null ? Optional.empty() : cachedPath;
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        refusalReason = refusalReason == null ? Optional.empty() : refusalReason;
    }
}
