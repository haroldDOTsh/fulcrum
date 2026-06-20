package sh.harold.fulcrum.capability.bundle;

import sh.harold.fulcrum.core.artifact.ArtifactObjectAddress;
import sh.harold.fulcrum.core.manifest.ArtifactPin;

import java.nio.file.Path;
import java.util.Objects;

public record VerifiedContributionBundle(
        ArtifactPin artifactPin,
        ArtifactObjectAddress objectAddress,
        Path cachedPath,
        ContributionBundleManifest manifest,
        BundleLoadDecision decision) {
    public VerifiedContributionBundle {
        artifactPin = Objects.requireNonNull(artifactPin, "artifactPin");
        objectAddress = Objects.requireNonNull(objectAddress, "objectAddress");
        cachedPath = Objects.requireNonNull(cachedPath, "cachedPath").toAbsolutePath().normalize();
        manifest = Objects.requireNonNull(manifest, "manifest");
        decision = Objects.requireNonNull(decision, "decision");
    }
}
