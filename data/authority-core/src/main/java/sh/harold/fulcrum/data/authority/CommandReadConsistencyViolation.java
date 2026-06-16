package sh.harold.fulcrum.data.authority;

public enum CommandReadConsistencyViolation {
    REJECTED_DECISION_HAS_NO_POST_WRITE_STATE,
    SYNC_PROJECTION_REVISION_MISMATCH,
    SYNC_PROJECTION_EMISSION_MISSING,
    SYNC_CACHE_WRITE_MISSING
}
