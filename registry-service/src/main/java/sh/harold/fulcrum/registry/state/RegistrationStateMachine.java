package sh.harold.fulcrum.registry.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.harold.fulcrum.registry.proxy.ProxyIdentifier;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * Thread-safe state machine for managing proxy registration states.
 * 
 * <p>This class manages the valid state transitions, tracks state history,
 * emits events, and handles automatic timeout transitions. All state changes
 * are atomic and thread-safe.
 * 
 * @author Harold
 * @since 1.0.0
 */
public class RegistrationStateMachine {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistrationStateMachine.class);
    
    private static final int MAX_HISTORY_SIZE = 10;
    private static final Duration REGISTRATION_TIMEOUT = Duration.ofSeconds(30);
    
    private final ProxyIdentifier proxyIdentifier;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final LinkedList<StateTransitionEvent> stateHistory = new LinkedList<>();
    private final List<Consumer<StateTransitionEvent>> eventListeners = new CopyOnWriteArrayList<>();
    private final Map<RegistrationState, Instant> stateEntryTimes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timeoutExecutor;
    
    private volatile RegistrationState currentState;
    private ScheduledFuture<?> timeoutFuture;
    
    /**
     * Valid state transitions map.
     */
    private static final Map<RegistrationState, Set<RegistrationState>> VALID_TRANSITIONS;
    
    static {
        Map<RegistrationState, Set<RegistrationState>> transitions = new EnumMap<>(RegistrationState.class);
        
        transitions.put(RegistrationState.UNREGISTERED,
            EnumSet.of(RegistrationState.REGISTERING));
            
        transitions.put(RegistrationState.REGISTERING,
            EnumSet.of(RegistrationState.REGISTERED, RegistrationState.FAILED));
            
        transitions.put(RegistrationState.REGISTERED,
            EnumSet.of(RegistrationState.RE_REGISTERING, 
                      RegistrationState.DEREGISTERING, 
                      RegistrationState.DISCONNECTED));
                      
        transitions.put(RegistrationState.RE_REGISTERING,
            EnumSet.of(RegistrationState.REGISTERED, RegistrationState.FAILED));
            
        transitions.put(RegistrationState.DEREGISTERING,
            EnumSet.of(RegistrationState.UNREGISTERED));
            
        transitions.put(RegistrationState.FAILED,
            EnumSet.of(RegistrationState.REGISTERING, RegistrationState.UNREGISTERED));
            
        transitions.put(RegistrationState.DISCONNECTED,
            EnumSet.of(RegistrationState.RE_REGISTERING, 
                      RegistrationState.DEREGISTERING, 
                      RegistrationState.FAILED));
        
        VALID_TRANSITIONS = Collections.unmodifiableMap(transitions);
    }
    
    /**
     * Creates a new state machine for the given proxy.
     * 
     * @param proxyIdentifier The proxy identifier
     * @param timeoutExecutor The executor for timeout handling
     */
    public RegistrationStateMachine(ProxyIdentifier proxyIdentifier,
                                   ScheduledExecutorService timeoutExecutor) {
        this.proxyIdentifier = Objects.requireNonNull(proxyIdentifier, "ProxyIdentifier cannot be null");
        this.timeoutExecutor = Objects.requireNonNull(timeoutExecutor, "Timeout executor cannot be null");
        this.currentState = RegistrationState.UNREGISTERED;
        this.stateEntryTimes.put(currentState, Instant.now());
    }
    
    /**
     * Creates a new state machine with a default single-threaded executor.
     * 
     * @param proxyIdentifier The proxy identifier
     */
    public RegistrationStateMachine(ProxyIdentifier proxyIdentifier) {
        this(proxyIdentifier, Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "StateMachine-Timeout-" + proxyIdentifier.getFormattedId());
            t.setDaemon(true);
            return t;
        }));
    }
    
    /**
     * Attempts to transition to a new state.
     * 
     * @param newState The target state
     * @param reason The reason for the transition
     * @return true if the transition was successful, false if invalid
     */
    public boolean transitionTo(RegistrationState newState, String reason) {
        return transitionTo(newState, reason, null);
    }
    
    /**
     * Attempts to transition to a new state with error information.
     * 
     * @param newState The target state
     * @param reason The reason for the transition
     * @param error The error that caused the transition (optional)
     * @return true if the transition was successful, false if invalid
     */
    public boolean transitionTo(RegistrationState newState, String reason, Throwable error) {
        Objects.requireNonNull(newState, "New state cannot be null");
        
        lock.writeLock().lock();
        try {
            if (!isValidTransition(currentState, newState)) {
                LOGGER.warn("Invalid state transition for proxy {}: {} -> {} (reason: {})",
                    proxyIdentifier.getFormattedId(), currentState, newState, reason);
                return false;
            }
            
            RegistrationState oldState = currentState;
            Instant entryTime = stateEntryTimes.get(oldState);
            long duration = entryTime != null 
                ? Duration.between(entryTime, Instant.now()).toMillis() 
                : 0;
            
            currentState = newState;
            stateEntryTimes.put(newState, Instant.now());
            
            StateTransitionEvent event = new StateTransitionEvent(
                proxyIdentifier, oldState, newState, reason, error, duration
            );
            
            addToHistory(event);
            
            LOGGER.info("State transition for proxy {}: {} -> {} (reason: {})",
                proxyIdentifier.getFormattedId(), oldState, newState, reason);
            
            handleTimeoutForState(newState);
            
            notifyListeners(event);
            
            return true;
            
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Gets the current state.
     * 
     * @return The current registration state
     */
    public RegistrationState getCurrentState() {
        lock.readLock().lock();
        try {
            return currentState;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Gets an immutable copy of the state history.
     * 
     * @return List of state transition events (newest first)
     */
    public List<StateTransitionEvent> getStateHistory() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(stateHistory);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Adds a listener for state transition events.
     * 
     * @param listener The event listener
     */
    public void addStateChangeListener(Consumer<StateTransitionEvent> listener) {
        eventListeners.add(Objects.requireNonNull(listener, "Listener cannot be null"));
    }
    
    /**
     * Removes a state change listener.
     * 
     * @param listener The listener to remove
     */
    public void removeStateChangeListener(Consumer<StateTransitionEvent> listener) {
        eventListeners.remove(listener);
    }
    
    /**
     * Gets the duration the proxy has been in the current state.
     * 
     * @return The duration in the current state
     */
    public Duration getTimeInCurrentState() {
        lock.readLock().lock();
        try {
            Instant entryTime = stateEntryTimes.get(currentState);
            return entryTime != null 
                ? Duration.between(entryTime, Instant.now())
                : Duration.ZERO;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Checks if the proxy is in an active state.
     * 
     * @return true if the proxy is registered or re-registering
     */
    public boolean isActive() {
        return getCurrentState().isActive();
    }
    
    /**
     * Checks if a transition is valid according to the state machine rules.
     * 
     * @param from The source state
     * @param to The target state
     * @return true if the transition is valid
     */
    public static boolean isValidTransition(RegistrationState from, RegistrationState to) {
        Set<RegistrationState> validTargets = VALID_TRANSITIONS.get(from);
        return validTargets != null && validTargets.contains(to);
    }
    
    /**
     * Gets all valid target states from the current state.
     * 
     * @return Set of valid target states
     */
    public Set<RegistrationState> getValidTransitions() {
        lock.readLock().lock();
        try {
            return VALID_TRANSITIONS.getOrDefault(currentState, EnumSet.noneOf(RegistrationState.class));
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Resets the state machine to UNREGISTERED state.
     * 
     * @param reason The reason for reset
     */
    public void reset(String reason) {
        lock.writeLock().lock();
        try {
            if (currentState != RegistrationState.UNREGISTERED) {
                RegistrationState oldState = currentState;
                currentState = RegistrationState.UNREGISTERED;
                stateEntryTimes.clear();
                stateEntryTimes.put(currentState, Instant.now());
                
                StateTransitionEvent event = new StateTransitionEvent(
                    proxyIdentifier, oldState, currentState, "Reset: " + reason
                );
                
                stateHistory.clear();
                addToHistory(event);
                
                cancelTimeout();
                
                LOGGER.info("State machine reset for proxy {}: {} -> UNREGISTERED (reason: {})",
                    proxyIdentifier.getFormattedId(), oldState, reason);
                
                notifyListeners(event);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Shuts down the state machine and releases resources.
     */
    public void shutdown() {
        cancelTimeout();
        if (timeoutExecutor instanceof ExecutorService) {
            ((ExecutorService) timeoutExecutor).shutdown();
            try {
                if (!((ExecutorService) timeoutExecutor).awaitTermination(5, TimeUnit.SECONDS)) {
                    ((ExecutorService) timeoutExecutor).shutdownNow();
                }
            } catch (InterruptedException e) {
                ((ExecutorService) timeoutExecutor).shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        eventListeners.clear();
    }
    
    private void addToHistory(StateTransitionEvent event) {
        stateHistory.addFirst(event);
        while (stateHistory.size() > MAX_HISTORY_SIZE) {
            stateHistory.removeLast();
        }
    }
    
    private void notifyListeners(StateTransitionEvent event) {
        for (Consumer<StateTransitionEvent> listener : eventListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                LOGGER.error("Error notifying state change listener for proxy {}",
                    proxyIdentifier.getFormattedId(), e);
            }
        }
    }
    
    private void handleTimeoutForState(RegistrationState state) {
        cancelTimeout();
        
        if (state == RegistrationState.REGISTERING || state == RegistrationState.RE_REGISTERING) {
            timeoutFuture = timeoutExecutor.schedule(() -> {
                transitionTo(RegistrationState.FAILED, "Registration timeout after " + 
                    REGISTRATION_TIMEOUT.getSeconds() + " seconds");
            }, REGISTRATION_TIMEOUT.getSeconds(), TimeUnit.SECONDS);
        }
    }
    
    private void cancelTimeout() {
        if (timeoutFuture != null && !timeoutFuture.isDone()) {
            timeoutFuture.cancel(false);
            timeoutFuture = null;
        }
    }
    
    @Override
    public String toString() {
        lock.readLock().lock();
        try {
            return String.format("StateMachine{proxy=%s, state=%s, timeInState=%s}",
                proxyIdentifier.getFormattedId(),
                currentState,
                getTimeInCurrentState());
        } finally {
            lock.readLock().unlock();
        }
    }
}