package sh.harold.fulcrum.data.artifact;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;
import java.util.Optional;

public record ArtifactMetadataState(Optional<ArtifactMetadata> metadata) {
    public ArtifactMetadataState {
        metadata = metadata == null ? Optional.empty() : metadata;
    }

    public ArtifactMetadataState(ArtifactMetadata metadata) {
        this(Optional.of(Objects.requireNonNull(metadata, "metadata")));
    }

    static ArtifactMetadataState empty() {
        return new ArtifactMetadataState(Optional.empty());
    }

    String wireValue(Revision revision) {
        return metadata.map(value -> "digest=" + value.digest().aggregateKey()
                        + "\nkind=" + value.kind().name()
                        + "\nbyteLength=" + value.byteLength()
                        + "\ncontentAddress=" + value.contentAddress().value()
                        + "\nproducerPrincipal=" + value.producerPrincipal().value()
                        + "\nprovenance=" + value.provenance().value()
                        + "\npublishedAt=" + value.publishedAt()
                        + "\nrevision=" + revision.value())
                .orElse("revision=" + revision.value());
    }
}
