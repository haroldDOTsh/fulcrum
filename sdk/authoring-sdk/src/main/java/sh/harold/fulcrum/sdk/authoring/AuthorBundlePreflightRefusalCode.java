package sh.harold.fulcrum.sdk.authoring;

public enum AuthorBundlePreflightRefusalCode {
    ARTIFACT_DIGEST_MISMATCH("authoring.artifact.digest-mismatch"),
    BYTECODE_FORBIDDEN_REFERENCE("authoring.bytecode.forbidden-reference"),
    CONTRACT_DUPLICATE_PROVIDER("authoring.contract.duplicate-provider"),
    CONTRACT_MISSING_PROVIDER("authoring.contract.missing-provider"),
    DEPENDENCY_FORBIDDEN_MODULE("authoring.dependency.forbidden-module"),
    DESCRIPTOR_DIGEST_MISMATCH("authoring.descriptor.digest-mismatch"),
    DESCRIPTOR_INVALID("authoring.descriptor.invalid"),
    GRANT_MISSING_AUTHORITY_DOMAIN("authoring.grant.missing-authority-domain"),
    GRANT_MISSING_RESOURCE_CLASS("authoring.grant.missing-resource-class"),
    PROVIDER_SHADOWED_SUBSTRATE_CLASS("authoring.provider.shadowed-substrate-class"),
    PROVIDER_UNDECLARED("authoring.provider.undeclared"),
    SCOPE_UNSUPPORTED("authoring.scope.unsupported"),
    SUBSTRATE_FINGERPRINT_MISMATCH("authoring.substrate.fingerprint-mismatch");

    private final String code;

    AuthorBundlePreflightRefusalCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
