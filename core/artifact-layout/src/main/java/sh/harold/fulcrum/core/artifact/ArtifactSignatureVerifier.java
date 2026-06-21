package sh.harold.fulcrum.core.artifact;

import java.io.IOException;

@FunctionalInterface
public interface ArtifactSignatureVerifier {
    ArtifactSignatureReceipt verify(
            ArtifactSourceRequest request,
            ArtifactDigestReference digest,
            byte[] bytes) throws IOException;
}
