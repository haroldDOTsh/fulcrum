package sh.harold.fulcrum.core.artifact;

public record ArtifactSignatureReceipt(
        boolean verified,
        String evidence) {
    public ArtifactSignatureReceipt {
        evidence = ArtifactLayoutNames.requireNonBlank(evidence, "evidence");
    }

    public static ArtifactSignatureReceipt verified(String evidence) {
        return new ArtifactSignatureReceipt(true, evidence);
    }

    public static ArtifactSignatureReceipt refused(String evidence) {
        return new ArtifactSignatureReceipt(false, evidence);
    }
}
