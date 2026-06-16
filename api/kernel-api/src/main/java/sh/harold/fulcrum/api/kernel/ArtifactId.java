package sh.harold.fulcrum.api.kernel;

public record ArtifactId(String value) {
    public ArtifactId {
        value = Ids.requireNonBlank(value, "artifactId");
    }
}
