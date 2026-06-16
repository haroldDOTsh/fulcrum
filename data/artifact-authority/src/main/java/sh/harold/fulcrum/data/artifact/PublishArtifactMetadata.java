package sh.harold.fulcrum.data.artifact;

import sh.harold.fulcrum.api.contract.CommandPayload;

import java.util.Objects;

public record PublishArtifactMetadata(
        ArtifactDigest digest,
        ArtifactKind kind,
        long byteLength,
        ContentAddress contentAddress,
        ProvenanceRef provenance) implements CommandPayload {
    public PublishArtifactMetadata {
        digest = Objects.requireNonNull(digest, "digest");
        kind = Objects.requireNonNull(kind, "kind");
        if (byteLength <= 0) {
            throw new IllegalArgumentException("byteLength must be positive");
        }
        contentAddress = Objects.requireNonNull(contentAddress, "contentAddress");
        provenance = Objects.requireNonNull(provenance, "provenance");
    }
}
