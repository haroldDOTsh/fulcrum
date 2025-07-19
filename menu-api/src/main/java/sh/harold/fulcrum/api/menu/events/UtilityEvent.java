package sh.harold.fulcrum.api.menu.events;

import sh.harold.fulcrum.api.menu.Menu;
import sh.harold.fulcrum.api.menu.util.MenuUtility;

import java.util.UUID;

/**
 * Base interface for all utility-specific events within the menu system.
 * UtilityEvents allow utilities to communicate with each other and respond
 * to various menu state changes and user interactions.
 * 
 * <p>This event system enables:
 * <ul>
 *   <li>Cross-utility communication and coordination</li>
 *   <li>Reactive utility behavior based on menu state changes</li>
 *   <li>Event-driven utility interactions</li>
 *   <li>Decoupled utility dependencies</li>
 * </ul>
 * 
 * <p>Events are dispatched through the {@link UtilityEventBus} and can be
 * listened to by any interested utility or system component.
 */
public interface UtilityEvent {
    
    /**
     * Gets the type of this utility event.
     * Used for event filtering and routing.
     * 
     * @return the event type
     */
    UtilityEventType getEventType();
    
    /**
     * Gets the menu this event is associated with.
     * 
     * @return the target menu, may be null for global events
     */
    Menu getMenu();
    
    /**
     * Gets the player this event is for.
     * 
     * @return the player UUID, may be null for global events
     */
    UUID getPlayerId();
    
    /**
     * Gets the utility that triggered this event.
     * 
     * @return the source utility, may be null for system events
     */
    MenuUtility getSourceUtility();
    
    /**
     * Gets the timestamp when this event was created.
     * 
     * @return the event timestamp in milliseconds
     */
    long getTimestamp();
    
    /**
     * Checks if this event has been handled.
     * 
     * @return true if handled, false otherwise
     */
    boolean isHandled();
    
    /**
     * Marks this event as handled.
     * Handled events may be ignored by some listeners.
     */
    void setHandled(boolean handled);
    
    /**
     * Checks if this event can be cancelled.
     * 
     * @return true if cancellable, false otherwise
     */
    boolean isCancellable();
    
    /**
     * Checks if this event has been cancelled.
     * 
     * @return true if cancelled, false otherwise
     */
    boolean isCancelled();
    
    /**
     * Sets the cancelled state of this event.
     * Only works if the event is cancellable.
     * 
     * @param cancelled true to cancel, false to uncancel
     * @throws UnsupportedOperationException if the event is not cancellable
     */
    void setCancelled(boolean cancelled);
    
    /**
     * Gets additional event data as a property.
     * 
     * @param key the property key
     * @param defaultValue the default value if not found
     * @param <T> the property type
     * @return the property value or default
     */
    <T> T getProperty(String key, T defaultValue);
    
    /**
     * Sets an event property.
     * 
     * @param key the property key
     * @param value the property value
     */
    void setProperty(String key, Object value);
    
    /**
     * Enumeration of utility event types.
     * Defines the various types of events that can occur in the utility system.
     */
    enum UtilityEventType {
        // Menu lifecycle events
        MENU_UTILITY_APPLIED("menu.utility.applied"),
        MENU_UTILITY_REMOVED("menu.utility.removed"),
        MENU_UTILITY_REFRESHED("menu.utility.refreshed"),
        
        // Pagination events
        PAGINATION_PAGE_CHANGED("pagination.page.changed"),
        PAGINATION_SIZE_CHANGED("pagination.size.changed"),
        PAGINATION_DATA_UPDATED("pagination.data.updated"),
        
        // Search events
        SEARCH_QUERY_CHANGED("search.query.changed"),
        SEARCH_RESULTS_UPDATED("search.results.updated"),
        SEARCH_MODE_TOGGLED("search.mode.toggled"),
        SEARCH_CLEARED("search.cleared"),
        
        // Sort/Filter events
        SORT_ORDER_CHANGED("sort.order.changed"),
        FILTER_APPLIED("filter.applied"),
        FILTER_REMOVED("filter.removed"),
        FILTER_CLEARED("filter.cleared"),
        
        // Navigation events
        NAVIGATION_BACK("navigation.back"),
        NAVIGATION_HOME("navigation.home"),
        NAVIGATION_CLOSE("navigation.close"),
        
        // Data events
        DATA_LOADED("data.loaded"),
        DATA_UPDATED("data.updated"),
        DATA_REFRESHED("data.refreshed"),
        
        // User interaction events
        UTILITY_CLICKED("utility.clicked"),
        UTILITY_HOVERED("utility.hovered"),
        UTILITY_ACTIVATED("utility.activated"),
        UTILITY_DEACTIVATED("utility.deactivated"),
        
        // Error events
        UTILITY_ERROR("utility.error"),
        VALIDATION_FAILED("validation.failed"),
        
        // Custom events
        CUSTOM("custom");
        
        private final String eventId;
        
        UtilityEventType(String eventId) {
            this.eventId = eventId;
        }
        
        public String getEventId() {
            return eventId;
        }
        
        /**
         * Gets an event type by its ID.
         * 
         * @param eventId the event ID
         * @return the matching event type, or CUSTOM if not found
         */
        public static UtilityEventType fromId(String eventId) {
            for (UtilityEventType type : values()) {
                if (type.eventId.equals(eventId)) {
                    return type;
                }
            }
            return CUSTOM;
        }
    }
    
    /**
     * Interface for listening to utility events.
     */
    interface UtilityEventListener {
        /**
         * Called when a utility event is dispatched.
         * 
         * @param event the utility event
         */
        void onUtilityEvent(UtilityEvent event);
        
        /**
         * Gets the priority of this listener.
         * Higher priority listeners are called first.
         * 
         * @return the listener priority (default: 0)
         */
        default int getPriority() {
            return 0;
        }
        
        /**
         * Checks if this listener is interested in the given event type.
         * 
         * @param eventType the event type
         * @return true if interested, false otherwise
         */
        default boolean isInterestedIn(UtilityEventType eventType) {
            return true; // By default, listen to all events
        }
    }
}