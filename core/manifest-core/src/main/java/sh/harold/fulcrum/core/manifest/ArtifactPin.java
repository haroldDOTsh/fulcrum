package sh.harold.fulcrum.core.manifest;

import sh.harold.fulcrum.api.kernel.ArtifactId;

import java.util.Objects;

public record ArtifactPin(ArtifactId artifactId, String digest, String compatibility) {
    public ArtifactPin {
        artifactId = Objects.requireNonNull(artifactId, "artifactId");
        digest = ManifestNames.requireNonBlank(digest, "digest");
        compatibility = ManifestNames.requireNonBlank(compatibility, "compatibility");
    }
}
