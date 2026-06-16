package sh.harold.fulcrum.control.route;

public enum RouteAttemptLifecycleStatus {
    CREATED,
    ISSUED_TO_PROXY,
    ISSUED_TO_HOST,
    HOST_ATTACH_OBSERVED,
    ACKED,
    TIMED_OUT,
    FAILED,
    CANCELLED
}
