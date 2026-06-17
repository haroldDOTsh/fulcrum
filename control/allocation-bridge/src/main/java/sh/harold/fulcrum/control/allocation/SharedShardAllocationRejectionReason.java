package sh.harold.fulcrum.control.allocation;

public enum SharedShardAllocationRejectionReason {
    IDEMPOTENCY_CONFLICT,
    ALLOCATION_UNAVAILABLE,
    INVALID_ALLOCATION_CLAIM
}
