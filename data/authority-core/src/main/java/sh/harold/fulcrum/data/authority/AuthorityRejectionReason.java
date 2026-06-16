package sh.harold.fulcrum.data.authority;

public enum AuthorityRejectionReason {
    DEADLINE_EXPIRED,
    PRINCIPAL_MISMATCH,
    STALE_FENCING_EPOCH,
    REVISION_MISMATCH,
    IDEMPOTENCY_CONFLICT
}
