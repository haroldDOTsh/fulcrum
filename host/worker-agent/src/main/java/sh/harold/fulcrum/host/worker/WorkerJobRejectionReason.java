package sh.harold.fulcrum.host.worker;

public enum WorkerJobRejectionReason {
    DEADLINE_EXPIRED,
    LAG_BUDGET_EXCEEDED,
    IDEMPOTENCY_CONFLICT
}
