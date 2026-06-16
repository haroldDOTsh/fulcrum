package sh.harold.fulcrum.api.kernel;

public record ResolvedManifestId(String value) {
    public ResolvedManifestId {
        value = Ids.requireNonBlank(value, "resolvedManifestId");
    }
}
