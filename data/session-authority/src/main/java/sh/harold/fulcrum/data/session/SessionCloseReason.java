package sh.harold.fulcrum.data.session;

public enum SessionCloseReason {
    COMPLETED,
    RELEASED,
    FAULTED,
    LEASE_EXPIRED;

    SessionLifecycleStatus terminalStatus() {
        return switch (this) {
            case COMPLETED, RELEASED -> SessionLifecycleStatus.ENDED;
            case FAULTED, LEASE_EXPIRED -> SessionLifecycleStatus.FAILED;
        };
    }
}
