package sh.harold.fulcrum.core.artifact;

public enum ArtifactVerificationStep {
    SOURCE_RESOLVED,
    DIGEST_PINNED,
    CACHE_HIT,
    CACHE_WRITTEN,
    CACHE_REHASHED,
    SIGNATURE_VERIFIED,
    UNSIGNED_LOCAL_IMPORT_ACCEPTED,
    REFUSED
}
