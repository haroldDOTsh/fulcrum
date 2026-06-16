package sh.harold.fulcrum.control.lifecycle;

public enum LifecycleTraceRejectionReason {
    PRINCIPAL_MISMATCH,
    STALE_FENCING_EPOCH,
    DEADLINE_EXPIRED,
    REVISION_MISMATCH,
    IDEMPOTENCY_CONFLICT,
    AGGREGATE_MISMATCH,
    CONTRACT_MISMATCH,
    TRACE_MISMATCH,
    UNKNOWN_COMMAND
}
