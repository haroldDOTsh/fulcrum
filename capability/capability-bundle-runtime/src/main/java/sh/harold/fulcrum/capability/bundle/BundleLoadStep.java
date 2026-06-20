package sh.harold.fulcrum.capability.bundle;

public enum BundleLoadStep {
    CACHE_HIT,
    PULLED,
    VERIFIED,
    MANIFEST_PARSED,
    MATERIALIZATION_MATCHED,
    PROVIDER_LOADED,
    REFUSED
}
