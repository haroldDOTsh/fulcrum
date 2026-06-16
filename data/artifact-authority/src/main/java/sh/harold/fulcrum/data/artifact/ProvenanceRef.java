package sh.harold.fulcrum.data.artifact;

public record ProvenanceRef(String value) {
    public ProvenanceRef {
        value = ArtifactNames.requireNonBlank(value);
    }
}
