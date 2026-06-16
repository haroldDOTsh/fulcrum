package sh.harold.fulcrum.data.artifact;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;

public record ArtifactMetadataPublished(
        ArtifactDigest digest,
        ArtifactKind kind,
        long byteLength,
        ContentAddress contentAddress,
        ProvenanceRef provenance,
        Revision revision) {
    public ArtifactMetadataPublished {
        digest = Objects.requireNonNull(digest, "digest");
        kind = Objects.requireNonNull(kind, "kind");
        if (byteLength <= 0) {
            throw new IllegalArgumentException("byteLength must be positive");
        }
        contentAddress = Objects.requireNonNull(contentAddress, "contentAddress");
        provenance = Objects.requireNonNull(provenance, "provenance");
        revision = Objects.requireNonNull(revision, "revision");
    }

    static ArtifactMetadataPublished from(ArtifactMetadata metadata, Revision revision) {
        return new ArtifactMetadataPublished(
                metadata.digest(),
                metadata.kind(),
                metadata.byteLength(),
                metadata.contentAddress(),
                metadata.provenance(),
                revision);
    }

    String wireValue() {
        return "digest=" + digest.aggregateKey()
                + "\nkind=" + kind.name()
                + "\nbyteLength=" + byteLength
                + "\ncontentAddress=" + contentAddress.value()
                + "\nprovenance=" + provenance.value()
                + "\nrevision=" + revision.value();
    }
}
