package sh.harold.fulcrum.api.menu.events;

import sh.harold.fulcrum.api.menu.Menu;
import sh.harold.fulcrum.api.menu.util.MenuUtility;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Event bus for managing and dispatching utility events within the menu system.
 * Provides a decoupled way for utilities to communicate with each other and
 * respond to system events.
 * 
 * <p>The UtilityEventBus supports:
 * <ul>
 *   <li>Asynchronous event dispatch</li>
 *   <li>Priority-based listener ordering</li>
 *   <li>Event filtering and routing</li>
 *   <li>Cancellable events</li>
 *   <li>Menu-scoped and global events</li>
 * </ul>
 * 
 * <p>Usage:
 * <pre>{@code
 * // Register a listener
 * eventBus.register(event -> {
 *     if (event.getEventType() == SEARCH_QUERY_CHANGED) {
 *         // Handle search query change
 *     }
 * });
 * 
 * // Dispatch an event
 * UtilityEvent event = new SearchQueryChangedEvent(menu, playerId, "search term");
 * eventBus.dispatch(event);
 * }</pre>
 */
public interface UtilityEventBus {
    
    /**
     * Registers a utility event listener.
     * Listeners are ordered by priority (higher priority first).
     * 
     * @param listener the event listener to register
     * @return a registration token that can be used to unregister
     */
    EventRegistration register(UtilityEvent.UtilityEventListener listener);
    
    /**
     * Registers a utility event listener with specific event type filtering.
     * 
     * @param listener the event listener to register
     * @param eventTypes the event types to listen for
     * @return a registration token that can be used to unregister
     */
    EventRegistration register(UtilityEvent.UtilityEventListener listener, 
                              Set<UtilityEvent.UtilityEventType> eventTypes);
    
    /**
     * Registers a utility event listener with custom filtering.
     * 
     * @param listener the event listener to register
     * @param filter custom predicate for filtering events
     * @return a registration token that can be used to unregister
     */
    EventRegistration register(UtilityEvent.UtilityEventListener listener, 
                              Predicate<UtilityEvent> filter);
    
    /**
     * Unregisters an event listener using its registration token.
     * 
     * @param registration the registration token from register()
     * @return true if the listener was found and removed, false otherwise
     */
    boolean unregister(EventRegistration registration);
    
    /**
     * Unregisters all listeners for a specific utility.
     * Useful for cleanup when a utility is removed.
     * 
     * @param utility the utility whose listeners should be removed
     * @return the number of listeners that were removed
     */
    int unregisterUtility(MenuUtility utility);
    
    /**
     * Dispatches an event to all registered listeners.
     * Events are dispatched asynchronously by default.
     * 
     * @param event the event to dispatch
     * @return a CompletableFuture that completes when all listeners have processed the event
     */
    CompletableFuture<Void> dispatch(UtilityEvent event);
    
    /**
     * Dispatches an event synchronously to all registered listeners.
     * Use with caution as this can block the calling thread.
     * 
     * @param event the event to dispatch
     */
    void dispatchSync(UtilityEvent event);
    
    /**
     * Dispatches an event to listeners within a specific menu scope.
     * Only listeners associated with the given menu will receive the event.
     * 
     * @param event the event to dispatch
     * @param menu the menu to scope the event to
     * @return a CompletableFuture that completes when all scoped listeners have processed the event
     */
    CompletableFuture<Void> dispatchScoped(UtilityEvent event, Menu menu);
    
    /**
     * Gets all currently registered listeners.
     * 
     * @return a set of all registered event listeners
     */
    Set<UtilityEvent.UtilityEventListener> getListeners();
    
    /**
     * Gets listeners filtered by event type.
     * 
     * @param eventType the event type to filter by
     * @return a set of listeners interested in the given event type
     */
    Set<UtilityEvent.UtilityEventListener> getListeners(UtilityEvent.UtilityEventType eventType);
    
    /**
     * Gets the number of currently registered listeners.
     * 
     * @return the listener count
     */
    int getListenerCount();
    
    /**
     * Checks if there are any listeners registered for a specific event type.
     * 
     * @param eventType the event type to check
     * @return true if there are listeners for this event type, false otherwise
     */
    boolean hasListenersFor(UtilityEvent.UtilityEventType eventType);
    
    /**
     * Clears all registered listeners.
     * This should be used carefully, typically only during shutdown.
     */
    void clear();
    
    /**
     * Gets statistics about event bus usage.
     * 
     * @return event bus statistics
     */
    EventBusStats getStats();
    
    /**
     * Creates a new event bus instance.
     * 
     * @return a new UtilityEventBus implementation
     */
    static UtilityEventBus create() {
        // Implementation is in player-core to keep API clean
        try {
            Class<?> eventBusClass = Class.forName("sh.harold.fulcrum.api.menu.events.DefaultUtilityEventBus");
            return (UtilityEventBus) eventBusClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create UtilityEventBus", e);
        }
    }
    
    /**
     * Token representing an event listener registration.
     * Used for unregistering listeners.
     */
    interface EventRegistration {
        /**
         * Gets the unique ID of this registration.
         * 
         * @return the registration ID
         */
        UUID getId();
        
        /**
         * Gets the listener associated with this registration.
         * 
         * @return the event listener
         */
        UtilityEvent.UtilityEventListener getListener();
        
        /**
         * Gets the timestamp when this registration was created.
         * 
         * @return the registration timestamp
         */
        long getTimestamp();
        
        /**
         * Checks if this registration is still active.
         * 
         * @return true if active, false if unregistered
         */
        boolean isActive();
        
        /**
         * Unregisters this listener.
         * This is equivalent to calling eventBus.unregister(this).
         * 
         * @return true if successfully unregistered, false if already unregistered
         */
        boolean unregister();
    }
    
    /**
     * Statistics about event bus usage and performance.
     */
    interface EventBusStats {
        /**
         * Gets the total number of events dispatched.
         * 
         * @return the total event count
         */
        long getTotalEventsDispatched();
        
        /**
         * Gets the number of events dispatched by type.
         * 
         * @param eventType the event type
         * @return the count for this event type
         */
        long getEventsDispatchedByType(UtilityEvent.UtilityEventType eventType);
        
        /**
         * Gets the current number of registered listeners.
         * 
         * @return the current listener count
         */
        int getCurrentListenerCount();
        
        /**
         * Gets the peak number of listeners ever registered.
         * 
         * @return the peak listener count
         */
        int getPeakListenerCount();
        
        /**
         * Gets the average event dispatch time in milliseconds.
         * 
         * @return the average dispatch time
         */
        double getAverageDispatchTimeMs();
        
        /**
         * Gets the number of failed event dispatches.
         * 
         * @return the failure count
         */
        long getFailedDispatches();
        
        /**
         * Resets all statistics.
         */
        void reset();
    }
}