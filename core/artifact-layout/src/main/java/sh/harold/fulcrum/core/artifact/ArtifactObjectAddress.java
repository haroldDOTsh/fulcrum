package sh.harold.fulcrum.core.artifact;

public record ArtifactObjectAddress(String value) {
    public ArtifactObjectAddress {
        value = ArtifactLayoutNames.requireNonBlank(value, "value");
        if (!value.startsWith("object://")) {
            throw new IllegalArgumentException("artifact object address must use the object scheme");
        }
    }
}
