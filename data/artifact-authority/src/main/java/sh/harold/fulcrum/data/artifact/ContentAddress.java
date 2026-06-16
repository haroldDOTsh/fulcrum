package sh.harold.fulcrum.data.artifact;

public record ContentAddress(String value) {
    public ContentAddress {
        value = ArtifactNames.requireNonBlank(value);
        if (!value.contains("://")) {
            throw new IllegalArgumentException("content address must include a scheme");
        }
    }
}
