package sh.harold.fulcrum.api.data;

import sh.harold.fulcrum.api.data.impl.DataAPIImpl;
import sh.harold.fulcrum.api.data.storage.ConnectionAdapter;
import sh.harold.fulcrum.api.data.storage.StorageBackend;
import sh.harold.fulcrum.api.data.transaction.Transaction;
import java.util.UUID;

/**
 * Main entry point for the Data API.
 * Provides fluent access to collections and documents.
 */
public interface DataAPI {
    
    /**
     * Factory method to create a DataAPI instance with the given connection adapter.
     *
     * @param adapter The connection adapter for storage backend
     * @return A new DataAPI instance
     */
    static DataAPI create(ConnectionAdapter adapter) {
        return DataAPIImpl.create(adapter);
    }
    
    /**
     * Access any collection by name.
     * 
     * @param collection The name of the collection
     * @return The collection interface
     */
    Collection from(String collection);
    
    /**
     * Alias for from() method - access any collection by name.
     * 
     * @param collection The name of the collection
     * @return The collection interface
     */
    Collection collection(String collection);
    
    /**
     * Direct access to the players collection.
     * 
     * @return The players collection
     */
    Collection players();
    
    /**
     * Direct access to a specific player document.
     * 
     * @param id The UUID of the player
     * @return The player document
     */
    Document player(UUID id);
    
    /**
     * Direct access to the guilds collection.
     *
     * @return The guilds collection
     */
    Collection guilds();
    
    /**
     * Start a new transaction.
     *
     * @return A new transaction instance
     */
    Transaction transaction();
    
    /**
     * Start a new transaction with specified isolation level.
     *
     * @param isolationLevel The isolation level for the transaction
     * @return A new transaction instance
     */
    Transaction transaction(Transaction.IsolationLevel isolationLevel);
    
    /**
     * Get the underlying storage backend backing this DataAPI instance.
     *
     * @return The storage backend implementation
     */
    StorageBackend getStorageBackend();
}
