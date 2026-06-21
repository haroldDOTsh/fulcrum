package sh.harold.fulcrum.sdk.authority;

public record AuthorityArtifactVerificationEvidence(
        boolean verified,
        String sourceKind,
        String sourceReference,
        String digest,
        String evidence) {
    public AuthorityArtifactVerificationEvidence {
        sourceKind = AuthoritySdkNames.requireNonBlank(sourceKind, "sourceKind");
        sourceReference = AuthoritySdkNames.requireNonBlank(sourceReference, "sourceReference");
        digest = AuthoritySdkNames.requireNonBlank(digest, "digest");
        evidence = AuthoritySdkNames.requireNonBlank(evidence, "evidence");
    }

    public static AuthorityArtifactVerificationEvidence verified(
            String sourceKind,
            String sourceReference,
            String digest,
            String evidence) {
        return new AuthorityArtifactVerificationEvidence(true, sourceKind, sourceReference, digest, evidence);
    }

    public String wireValue() {
        return "verified=" + verified
                + "|sourceKind=" + sourceKind
                + "|sourceReference=" + sourceReference
                + "|digest=" + digest
                + "|evidence=" + evidence;
    }
}
