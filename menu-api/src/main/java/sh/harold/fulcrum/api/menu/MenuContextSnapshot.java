package sh.harold.fulcrum.api.menu;

import java.util.Map;

/**
 * Represents a snapshot of menu context state at a specific point in time.
 * Used for saving and restoring menu state during navigation or refresh operations.
 */
public interface MenuContextSnapshot {
    
    /**
     * Gets the menu ID from this snapshot.
     * 
     * @return the menu ID
     */
    String getMenuId();
    
    /**
     * Gets the timestamp when this snapshot was created.
     * 
     * @return the creation timestamp in milliseconds
     */
    long getSnapshotTimestamp();
    
    /**
     * Gets all properties stored in this snapshot.
     * 
     * @return an immutable map of properties
     */
    Map<String, Object> getProperties();
    
    /**
     * Gets a specific property from the snapshot.
     * 
     * @param key the property key
     * @param <T> the expected type
     * @return the property value or null if not present
     */
    @SuppressWarnings("unchecked")
    default <T> T getProperty(String key) {
        return (T) getProperties().get(key);
    }
    
    /**
     * Checks if this snapshot contains a specific property.
     * 
     * @param key the property key
     * @return true if the property exists, false otherwise
     */
    default boolean hasProperty(String key) {
        return getProperties().containsKey(key);
    }
    
    /**
     * Gets the age of this snapshot in milliseconds.
     * 
     * @return the age in milliseconds
     */
    default long getAge() {
        return System.currentTimeMillis() - getSnapshotTimestamp();
    }
    
    /**
     * Checks if this snapshot is older than the specified duration.
     * 
     * @param maxAgeMillis the maximum age in milliseconds
     * @return true if the snapshot is older than maxAgeMillis
     */
    default boolean isOlderThan(long maxAgeMillis) {
        return getAge() > maxAgeMillis;
    }
}