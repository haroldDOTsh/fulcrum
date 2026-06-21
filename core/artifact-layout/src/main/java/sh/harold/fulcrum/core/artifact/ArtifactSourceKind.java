package sh.harold.fulcrum.core.artifact;

public enum ArtifactSourceKind {
    OCI(true),
    PRIVATE_OCI(true),
    TARBALL(false),
    LOCAL_FILE(false);

    private final boolean productionEligible;

    ArtifactSourceKind(boolean productionEligible) {
        this.productionEligible = productionEligible;
    }

    public boolean productionEligible() {
        return productionEligible;
    }
}
