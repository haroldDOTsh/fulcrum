package sh.harold.fulcrum.core.artifact;

import java.util.Arrays;
import java.util.Optional;

public record ArtifactSourceBytes(
        byte[] bytes,
        Optional<String> resolvedDigest,
        String evidence) {
    public ArtifactSourceBytes {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("artifact source bytes must not be empty");
        }
        bytes = Arrays.copyOf(bytes, bytes.length);
        resolvedDigest = resolvedDigest == null ? Optional.empty() : resolvedDigest
                .map(digest -> ArtifactLayoutNames.requireNonBlank(digest, "resolvedDigest"));
        evidence = ArtifactLayoutNames.requireNonBlank(evidence, "evidence");
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }
}
