package sh.harold.fulcrum.registry.state;

import sh.harold.fulcrum.registry.proxy.ProxyIdentifier;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents a state transition event in the proxy registration lifecycle.
 *
 * <p>This event captures all information about a state change, including
 * the previous and new states, timing, and any associated metadata or error
 * information.
 *
 * @author Harold
 * @since 1.0.0
 */
public class StateTransitionEvent {

    private final ProxyIdentifier proxyIdentifier;
    private final RegistrationState fromState;
    private final RegistrationState toState;
    private final Instant timestamp;
    private final String reason;
    private final Throwable error;
    private final long transitionDurationMs;

    /**
     * Creates a new state transition event.
     *
     * @param proxyIdentifier The proxy identifier
     * @param fromState       The previous state
     * @param toState         The new state
     * @param reason          The reason for the transition (optional)
     */
    public StateTransitionEvent(ProxyIdentifier proxyIdentifier,
                                RegistrationState fromState,
                                RegistrationState toState,
                                String reason) {
        this(proxyIdentifier, fromState, toState, reason, null, 0);
    }

    /**
     * Creates a new state transition event with error information.
     *
     * @param proxyIdentifier      The proxy identifier
     * @param fromState            The previous state
     * @param toState              The new state
     * @param reason               The reason for the transition
     * @param error                The error that caused the transition (optional)
     * @param transitionDurationMs The duration of the transition in milliseconds
     */
    public StateTransitionEvent(ProxyIdentifier proxyIdentifier,
                                RegistrationState fromState,
                                RegistrationState toState,
                                String reason,
                                Throwable error,
                                long transitionDurationMs) {
        this.proxyIdentifier = Objects.requireNonNull(proxyIdentifier, "ProxyIdentifier cannot be null");
        this.fromState = Objects.requireNonNull(fromState, "From state cannot be null");
        this.toState = Objects.requireNonNull(toState, "To state cannot be null");
        this.timestamp = Instant.now();
        this.reason = reason;
        this.error = error;
        this.transitionDurationMs = transitionDurationMs;
    }

    /**
     * Gets the proxy identifier.
     *
     * @return The proxy identifier
     */
    public ProxyIdentifier getProxyIdentifier() {
        return proxyIdentifier;
    }

    /**
     * Gets the previous state.
     *
     * @return The state before the transition
     */
    public RegistrationState getFromState() {
        return fromState;
    }

    /**
     * Gets the new state.
     *
     * @return The state after the transition
     */
    public RegistrationState getToState() {
        return toState;
    }

    /**
     * Gets the timestamp of the transition.
     *
     * @return The transition timestamp
     */
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * Gets the reason for the transition.
     *
     * @return The transition reason, or empty if not specified
     */
    public Optional<String> getReason() {
        return Optional.ofNullable(reason);
    }

    /**
     * Gets any error associated with the transition.
     *
     * @return The error, or empty if no error
     */
    public Optional<Throwable> getError() {
        return Optional.ofNullable(error);
    }

    /**
     * Gets the duration of the transition.
     *
     * @return The transition duration in milliseconds
     */
    public long getTransitionDurationMs() {
        return transitionDurationMs;
    }

    /**
     * Checks if this transition represents a failure.
     *
     * @return true if the transition is to the FAILED state or has an error
     */
    public boolean isFailure() {
        return toState == RegistrationState.FAILED || error != null;
    }

    /**
     * Checks if this transition represents a recovery.
     *
     * @return true if transitioning from a failed/disconnected state to active
     */
    public boolean isRecovery() {
        return (fromState == RegistrationState.FAILED || fromState == RegistrationState.DISCONNECTED)
                && toState.isActive();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("StateTransition{");
        sb.append("proxy=").append(proxyIdentifier.getFormattedId());
        sb.append(", ").append(fromState).append(" -> ").append(toState);
        sb.append(", timestamp=").append(timestamp);
        if (reason != null) {
            sb.append(", reason='").append(reason).append("'");
        }
        if (error != null) {
            sb.append(", error=").append(error.getClass().getSimpleName());
        }
        if (transitionDurationMs > 0) {
            sb.append(", duration=").append(transitionDurationMs).append("ms");
        }
        sb.append('}');
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StateTransitionEvent)) return false;
        StateTransitionEvent that = (StateTransitionEvent) o;
        return transitionDurationMs == that.transitionDurationMs &&
                proxyIdentifier.equals(that.proxyIdentifier) &&
                fromState == that.fromState &&
                toState == that.toState &&
                timestamp.equals(that.timestamp) &&
                Objects.equals(reason, that.reason) &&
                Objects.equals(error, that.error);
    }

    @Override
    public int hashCode() {
        return Objects.hash(proxyIdentifier, fromState, toState, timestamp);
    }
}