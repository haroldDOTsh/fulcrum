package sh.harold.fulcrum.registry.state;

/**
 * Represents the various states of proxy registration lifecycle.
 * 
 * <p>Each state represents a specific phase in the proxy registration process,
 * from initial unregistered state through registration, active operation,
 * and eventual deregistration or failure.
 * 
 * @author Harold
 * @since 1.0.0
 */
public enum RegistrationState {
    
    /**
     * Initial state - proxy has not started registration process.
     * Valid transitions: REGISTERING
     */
    UNREGISTERED("Proxy has not initiated registration"),
    
    /**
     * Registration is currently in progress.
     * Valid transitions: REGISTERED, FAILED
     */
    REGISTERING("Registration process is active"),
    
    /**
     * Proxy is successfully registered and operational.
     * Valid transitions: RE_REGISTERING, DEREGISTERING, DISCONNECTED
     */
    REGISTERED("Proxy is registered and active"),
    
    /**
     * Re-registration is in progress (e.g., after temporary disconnection).
     * Valid transitions: REGISTERED, FAILED
     */
    RE_REGISTERING("Re-registration process is active"),
    
    /**
     * Deregistration is in progress.
     * Valid transitions: UNREGISTERED
     */
    DEREGISTERING("Deregistration process is active"),
    
    /**
     * Registration attempt failed.
     * Valid transitions: REGISTERING, UNREGISTERED
     */
    FAILED("Registration failed"),
    
    /**
     * Connection lost but registration may be recoverable.
     * Valid transitions: RE_REGISTERING, DEREGISTERING, FAILED
     */
    DISCONNECTED("Connection lost");
    
    private final String description;
    
    RegistrationState(String description) {
        this.description = description;
    }
    
    /**
     * Gets the human-readable description of this state.
     * 
     * @return The state description
     */
    public String getDescription() {
        return description;
    }
    
    /**
     * Checks if this state represents an active registration.
     * 
     * @return true if the proxy is registered or re-registering
     */
    public boolean isActive() {
        return this == REGISTERED || this == RE_REGISTERING;
    }
    
    /**
     * Checks if this state represents a terminal state.
     * 
     * @return true if this is a terminal state
     */
    public boolean isTerminal() {
        return this == UNREGISTERED || this == FAILED;
    }
    
    /**
     * Checks if this state represents a transitional state.
     * 
     * @return true if this is a transitional state
     */
    public boolean isTransitional() {
        return this == REGISTERING || this == RE_REGISTERING || this == DEREGISTERING;
    }
}