package sh.harold.fulcrum.control.allocation;

public enum RosterAllocationRejectionReason {
    IDEMPOTENCY_CONFLICT,
    ALLOCATION_UNAVAILABLE,
    INVALID_ALLOCATION_CLAIM
}
