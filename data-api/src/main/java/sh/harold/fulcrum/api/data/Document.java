package sh.harold.fulcrum.api.data;

import java.util.Map;

/**
 * Interface representing a single document in a collection.
 * Provides methods for accessing and modifying document data.
 */
public interface Document {
    
    /**
     * Get a value from the document by path.
     * Supports nested paths like "user.profile.name".
     * 
     * @param path The path to the value
     * @return The value at the path, or null if not found
     */
    Object get(String path);
    
    /**
     * Get a value from the document by path with a default value.
     * 
     * @param <T> The type of the value
     * @param path The path to the value
     * @param defaultValue The default value if path doesn't exist
     * @return The value at the path, or defaultValue if not found
     */
    <T> T get(String path, T defaultValue);
    
    /**
     * Set a value in the document at the given path.
     * Supports nested paths and will create intermediate objects as needed.
     * 
     * @param path The path where to set the value
     * @param value The value to set
     * @return This document for chaining
     */
    Document set(String path, Object value);
    
    /**
     * Check if this document exists in the storage backend.
     * 
     * @return true if the document exists, false otherwise
     */
    boolean exists();
    
    /**
     * Convert the document to a Map representation.
     * 
     * @return Map containing all document data
     */
    Map<String, Object> toMap();
    
    /**
     * Convert the document to a JSON string.
     * 
     * @return JSON representation of the document
     */
    String toJson();
}