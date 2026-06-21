package sh.harold.fulcrum.core.artifact;

public record ArtifactSourcePolicy(
        boolean productionMode,
        boolean requireSignature,
        boolean allowUnsignedLocalImport) {
    public ArtifactSourcePolicy {
        if (productionMode && !requireSignature) {
            throw new IllegalArgumentException("production artifact policy must require signatures");
        }
        if (productionMode && allowUnsignedLocalImport) {
            throw new IllegalArgumentException("production artifact policy cannot allow unsigned local imports");
        }
    }

    public static ArtifactSourcePolicy production() {
        return new ArtifactSourcePolicy(true, true, false);
    }

    public static ArtifactSourcePolicy localDevelopment() {
        return new ArtifactSourcePolicy(false, false, true);
    }
}
