package sh.harold.fulcrum.core.artifact;

import java.io.IOException;

@FunctionalInterface
public interface ArtifactBytesResolver {
    ArtifactSourceBytes resolve(ArtifactSourceRequest request) throws IOException;
}
