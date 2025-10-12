package sh.harold.fulcrum.api.data.storage;

import com.mongodb.client.MongoDatabase;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Adapter interface for different storage backends.
 * Provides connection details and configuration for MongoDB or JSON storage.
 */
public interface ConnectionAdapter {
    
    /**
     * Get the storage type for this adapter.
     * 
     * @return The storage type (MONGODB, JSON, or IN_MEMORY)
     */
    StorageType getStorageType();
    
    /**
     * Get the MongoDB database instance.
     * Only applicable for MongoDB storage type.
     * 
     * @return The MongoDB database, or null if not MongoDB storage
     */
    MongoDatabase getMongoDatabase();
    
    /**
     * Get the path for JSON file storage.
     * Only applicable for JSON storage type.
     * 
     * @return The path to JSON storage directory, or null if not JSON storage
     */
    Path getJsonStoragePath();
    
    /**
     * Get the optional cache provider for this connection.
     * 
     * @return Optional cache provider
     */
    Optional<CacheProvider> getCacheProvider();
}
