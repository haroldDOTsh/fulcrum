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
     * PostgreSQL database storage backend
     */
    POSTGRES,
    
    /**
     * JSON file-based storage backend
     */
    JSON,
    
    /**
     * In-memory storage backend (for testing)
     */
    IN_MEMORY
}