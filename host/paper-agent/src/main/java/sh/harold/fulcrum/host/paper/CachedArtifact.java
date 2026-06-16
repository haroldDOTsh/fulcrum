package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.core.manifest.ArtifactPin;

import java.nio.file.Path;
import java.util.Objects;

public record CachedArtifact(
        ArtifactPin artifactPin,
        Path cachedPath,
        String verifiedDigest,
        boolean cacheHit) {
    public CachedArtifact {
        artifactPin = Objects.requireNonNull(artifactPin, "artifactPin");
        cachedPath = Objects.requireNonNull(cachedPath, "cachedPath");
        verifiedDigest = PaperArtifactNames.requireNonBlank(verifiedDigest, "verifiedDigest");
    }
}
