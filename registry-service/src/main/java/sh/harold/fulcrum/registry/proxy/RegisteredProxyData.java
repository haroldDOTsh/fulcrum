package sh.harold.fulcrum.registry.proxy;

import sh.harold.fulcrum.registry.state.RegistrationState;
import sh.harold.fulcrum.registry.state.RegistrationStateMachine;
import sh.harold.fulcrum.registry.state.StateTransitionEvent;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Information about a registered proxy with state management.
 */
public class RegisteredProxyData {
    
    /**
     * Proxy status states matching server states
     */
    public enum Status {
        AVAILABLE,
        UNAVAILABLE,
        DEAD
    }
    
    private final ProxyIdentifier proxyId;
    private final String address;
    private final int port;
    private final RegistrationStateMachine stateMachine;
    private long lastHeartbeat;
    private volatile Status status;
    
    public RegisteredProxyData(ProxyIdentifier proxyId, String address, int port,
                              ScheduledExecutorService timeoutExecutor) {
        this.proxyId = Objects.requireNonNull(proxyId, "ProxyIdentifier cannot be null");
        this.address = Objects.requireNonNull(address, "Address cannot be null");
        this.port = port;
        this.lastHeartbeat = System.currentTimeMillis();
        this.status = Status.AVAILABLE;
        this.stateMachine = new RegistrationStateMachine(proxyId, timeoutExecutor);
    }
    
    /**
     * Constructor with default state machine (creates its own timeout executor).
     */
    public RegisteredProxyData(ProxyIdentifier proxyId, String address, int port) {
        this.proxyId = Objects.requireNonNull(proxyId, "ProxyIdentifier cannot be null");
        this.address = Objects.requireNonNull(address, "Address cannot be null");
        this.port = port;
        this.lastHeartbeat = System.currentTimeMillis();
        this.status = Status.AVAILABLE;
        this.stateMachine = new RegistrationStateMachine(proxyId);
    }
    
    /**
     * Legacy constructor for backward compatibility during migration.
     * @deprecated Use constructor with ProxyIdentifier instead
     */
    @Deprecated
    public RegisteredProxyData(String legacyProxyId, String address, int port) {
        this(ProxyIdentifier.fromLegacy(legacyProxyId), address, port);
    }
    
    public ProxyIdentifier getProxyId() {
        return proxyId;
    }
    
    /**
     * Gets the proxy ID as a formatted string.
     * @return The formatted proxy ID string
     */
    public String getProxyIdString() {
        return proxyId.getFormattedId();
    }
    
    public String getAddress() {
        return address;
    }
    
    public int getPort() {
        return port;
    }
    
    public long getLastHeartbeat() {
        return lastHeartbeat;
    }
    
    public void setLastHeartbeat(long lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }
    
    public Status getStatus() {
        return status;
    }
    
    public void setStatus(Status status) {
        this.status = status;
    }
    
    /**
     * Gets the registration state machine for this proxy.
     *
     * @return The state machine
     */
    public RegistrationStateMachine getStateMachine() {
        return stateMachine;
    }
    
    /**
     * Gets the current registration state.
     *
     * @return The current state
     */
    public RegistrationState getRegistrationState() {
        return stateMachine.getCurrentState();
    }
    
    /**
     * Gets the state transition history.
     *
     * @return List of state transitions (newest first)
     */
    public List<StateTransitionEvent> getStateHistory() {
        return stateMachine.getStateHistory();
    }
    
    /**
     * Attempts to transition to a new registration state.
     *
     * @param newState The target state
     * @param reason The reason for the transition
     * @return true if the transition was successful
     */
    public boolean transitionTo(RegistrationState newState, String reason) {
        return stateMachine.transitionTo(newState, reason);
    }
    
    /**
     * Shuts down the state machine and releases resources.
     */
    public void shutdown() {
        stateMachine.shutdown();
    }
}