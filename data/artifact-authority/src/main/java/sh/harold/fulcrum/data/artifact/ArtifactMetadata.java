package sh.harold.fulcrum.data.artifact;

import sh.harold.fulcrum.api.contract.PrincipalId;

import java.time.Instant;
import java.util.Objects;

public record ArtifactMetadata(
        ArtifactDigest digest,
        ArtifactKind kind,
        long byteLength,
        ContentAddress contentAddress,
        PrincipalId producerPrincipal,
        ProvenanceRef provenance,
        Instant publishedAt) {
    public ArtifactMetadata {
        digest = Objects.requireNonNull(digest, "digest");
        kind = Objects.requireNonNull(kind, "kind");
        if (byteLength <= 0) {
            throw new IllegalArgumentException("byteLength must be positive");
        }
        contentAddress = Objects.requireNonNull(contentAddress, "contentAddress");
        producerPrincipal = Objects.requireNonNull(producerPrincipal, "producerPrincipal");
        provenance = Objects.requireNonNull(provenance, "provenance");
        publishedAt = Objects.requireNonNull(publishedAt, "publishedAt");
    }
}
