package sh.harold.fulcrum.api.data.storage;

/**
 * Enumeration of supported storage backend types.
 */
public enum StorageType {
    /**
     * MongoDB database storage backend
     */
    MONGODB,
    
    /**
     * JSON file-based storage backend (typically for local testing)
     */
    JSON,
    
    /**
     * In-memory storage backend (for testing)
     */
    IN_MEMORY
}
