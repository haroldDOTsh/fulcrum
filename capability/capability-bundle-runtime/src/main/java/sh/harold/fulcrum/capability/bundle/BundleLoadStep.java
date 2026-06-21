package sh.harold.fulcrum.capability.bundle;

public enum BundleLoadStep {
    SOURCE_NORMALIZED,
    CACHE_HIT,
    PULLED,
    SIGNATURE_VERIFIED,
    VERIFIED,
    MANIFEST_PARSED,
    MATERIALIZATION_MATCHED,
    PROVIDER_LOADED,
    REFUSED
}
