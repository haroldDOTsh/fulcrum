package sh.harold.fulcrum.api.menu;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Optional;

/**
 * Represents the context and state of a menu instance.
 * Stores metadata, properties, and runtime state for menus.
 */
public interface MenuContext {
    
    /**
     * Gets the menu ID associated with this context.
     * 
     * @return the menu ID
     */
    String getMenuId();
    
    /**
     * Gets the player viewing the menu.
     * 
     * @return the player
     */
    Player getViewer();
    
    /**
     * Gets a property value from the context.
     * 
     * @param key the property key
     * @param type the expected type of the value
     * @param <T> the type parameter
     * @return an Optional containing the value if present and of the correct type
     */
    <T> Optional<T> getProperty(String key, Class<T> type);
    
    /**
     * Sets a property value in the context.
     * 
     * @param key the property key
     * @param value the property value
     */
    void setProperty(String key, Object value);
    
    /**
     * Removes a property from the context.
     * 
     * @param key the property key
     * @return the previous value if present
     */
    Object removeProperty(String key);
    
    /**
     * Checks if a property exists in the context.
     * 
     * @param key the property key
     * @return true if the property exists, false otherwise
     */
    boolean hasProperty(String key);
    
    /**
     * Gets all properties as an unmodifiable map.
     * 
     * @return map of all properties
     */
    Map<String, Object> getProperties();
    
    /**
     * Clears all properties from the context.
     */
    void clearProperties();
    
    /**
     * Gets the current page number for paginated menus.
     * 
     * @return the current page (1-based)
     */
    default int getCurrentPage() {
        return getProperty("currentPage", Integer.class).orElse(1);
    }
    
    /**
     * Sets the current page number for paginated menus.
     * 
     * @param page the page number (1-based)
     */
    default void setCurrentPage(int page) {
        setProperty("currentPage", page);
    }
    
    /**
     * Gets the total number of pages for paginated menus.
     * 
     * @return the total pages
     */
    default int getTotalPages() {
        return getProperty("totalPages", Integer.class).orElse(1);
    }
    
    /**
     * Sets the total number of pages for paginated menus.
     * 
     * @param totalPages the total pages
     */
    default void setTotalPages(int totalPages) {
        setProperty("totalPages", totalPages);
    }
    
    /**
     * Gets the viewport offset for custom menus.
     * 
     * @return the viewport offset
     */
    default int getViewportOffset() {
        return getProperty("viewportOffset", Integer.class).orElse(0);
    }
    
    /**
     * Sets the viewport offset for custom menus.
     * 
     * @param offset the viewport offset
     */
    default void setViewportOffset(int offset) {
        setProperty("viewportOffset", offset);
    }
    
    /**
     * Gets the timestamp when the menu was opened.
     * 
     * @return the open timestamp in milliseconds
     */
    long getOpenTimestamp();
    
    /**
     * Gets the elapsed time since the menu was opened.
     * 
     * @return the elapsed time in milliseconds
     */
    default long getElapsedTime() {
        return System.currentTimeMillis() - getOpenTimestamp();
    }
    
}