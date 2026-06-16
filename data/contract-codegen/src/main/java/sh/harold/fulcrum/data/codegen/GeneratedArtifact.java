package sh.harold.fulcrum.data.codegen;

public record GeneratedArtifact(String path, String contents) {
    public GeneratedArtifact {
        path = CodegenNames.requireNonBlank(path, "path");
        contents = contents == null ? "" : contents;
    }
}
