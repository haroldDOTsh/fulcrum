package sh.harold.fulcrum.capability.bundle;

import sh.harold.fulcrum.core.artifact.ArtifactObjectAddress;

import java.io.IOException;
import java.util.Optional;

@FunctionalInterface
public interface BundleArtifactSource {
    Optional<byte[]> read(ArtifactObjectAddress address) throws IOException;
}
