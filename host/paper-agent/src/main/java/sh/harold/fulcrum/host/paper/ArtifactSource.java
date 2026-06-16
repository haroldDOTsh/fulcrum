package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.api.kernel.ArtifactId;

import java.io.IOException;

@FunctionalInterface
public interface ArtifactSource {
    byte[] read(ArtifactId artifactId) throws IOException;
}
