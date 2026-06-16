package sh.harold.fulcrum.data.artifact;

import java.util.Locale;

public record ArtifactDigest(
        String algorithm,
        String value) {
    public ArtifactDigest {
        algorithm = ArtifactNames.requireNonBlank(algorithm).toLowerCase(Locale.ROOT);
        value = ArtifactNames.requireNonBlank(value).toLowerCase(Locale.ROOT);
        if (!algorithm.matches("[a-z0-9][a-z0-9-]{1,31}")) {
            throw new IllegalArgumentException("digest algorithm must be a stable token");
        }
        if (!value.matches("[a-f0-9]{32,128}")) {
            throw new IllegalArgumentException("digest value must be lowercase hexadecimal");
        }
    }

    String aggregateKey() {
        return algorithm + ":" + value;
    }
}
