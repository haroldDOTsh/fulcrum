package sh.harold.fulcrum.adapters.objectstorage;

import sh.harold.fulcrum.core.artifact.ArtifactDigestReference;
import sh.harold.fulcrum.core.artifact.ArtifactObjectAddress;

import java.util.Objects;

public record StoredObject(
        ArtifactObjectAddress address,
        long byteLength,
        ArtifactDigestReference digest) {
    public StoredObject {
        address = Objects.requireNonNull(address, "address");
        if (byteLength < 0) {
            throw new IllegalArgumentException("byteLength must not be negative");
        }
        digest = Objects.requireNonNull(digest, "digest");
    }
}
