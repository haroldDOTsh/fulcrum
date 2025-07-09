package sh.harold.fulcrum.api.data.dirty;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Main abstraction for dirty data caching.
 * 
 * This interface provides methods to track data changes, check if data is dirty,
 * and manage the dirty data lifecycle. Implementations should be thread-safe.
 */
public interface DirtyDataCache {
    
    /**
     * Marks data as dirty for the specified player and schema.
     * 
     * @param playerId The ID of the player whose data is dirty
     * @param schemaKey The schema key identifying the type of data
     * @param data The actual data object that was modified
     * @param changeType The type of change that occurred
     * @throws IllegalArgumentException if any required parameter is null
     */
    void markDirty(UUID playerId, String schemaKey, Object data, DirtyDataEntry.ChangeType changeType);
    
    /**
     * Checks if data is dirty for the specified player and schema.
     * 
     * @param playerId The ID of the player to check
     * @param schemaKey The schema key to check
     * @return true if the data is dirty, false otherwise
     * @throws IllegalArgumentException if playerId or schemaKey is null
     */
    boolean isDirty(UUID playerId, String schemaKey);
    
    /**
     * Checks if any data is dirty for the specified player.
     * 
     * @param playerId The ID of the player to check
     * @return true if any data is dirty for the player, false otherwise
     * @throws IllegalArgumentException if playerId is null
     */
    boolean isDirty(UUID playerId);
    
    /**
     * Gets all dirty data entries for the specified player.
     * 
     * @param playerId The ID of the player
     * @return A collection of dirty data entries, empty if none exist
     * @throws IllegalArgumentException if playerId is null
     */
    Collection<DirtyDataEntry> getDirtyEntries(UUID playerId);
    
    /**
     * Gets all dirty data entries across all players.
     * 
     * @return A collection of all dirty data entries
     */
    Collection<DirtyDataEntry> getAllDirtyEntries();
    
    /**
     * Gets dirty data entries that are older than the specified threshold.
     * 
     * @param threshold The timestamp threshold
     * @return A collection of entries older than the threshold
     * @throws IllegalArgumentException if threshold is null
     */
    Collection<DirtyDataEntry> getDirtyEntriesOlderThan(Instant threshold);
    
    /**
     * Clears the dirty flag for the specified player and schema.
     * 
     * @param playerId The ID of the player
     * @param schemaKey The schema key
     * @throws IllegalArgumentException if playerId or schemaKey is null
     */
    void clearDirty(UUID playerId, String schemaKey);
    
    /**
     * Clears all dirty flags for the specified player.
     * 
     * @param playerId The ID of the player
     * @throws IllegalArgumentException if playerId is null
     */
    void clearDirty(UUID playerId);
    
    /**
     * Clears all dirty flags across all players.
     */
    void clearAllDirty();
    
    /**
     * Gets the number of dirty entries for the specified player.
     * 
     * @param playerId The ID of the player
     * @return The number of dirty entries
     * @throws IllegalArgumentException if playerId is null
     */
    int getDirtyCount(UUID playerId);
    
    /**
     * Gets the total number of dirty entries across all players.
     * 
     * @return The total number of dirty entries
     */
    int getTotalDirtyCount();
    
    /**
     * Asynchronously marks data as dirty.
     * 
     * @param playerId The ID of the player whose data is dirty
     * @param schemaKey The schema key identifying the type of data
     * @param data The actual data object that was modified
     * @param changeType The type of change that occurred
     * @return A CompletableFuture that completes when the operation is done
     * @throws IllegalArgumentException if any required parameter is null
     */
    default CompletableFuture<Void> markDirtyAsync(UUID playerId, String schemaKey, Object data, DirtyDataEntry.ChangeType changeType) {
        return CompletableFuture.runAsync(() -> markDirty(playerId, schemaKey, data, changeType));
    }
    
    /**
     * Asynchronously gets all dirty data entries for the specified player.
     * 
     * @param playerId The ID of the player
     * @return A CompletableFuture containing the collection of dirty data entries
     * @throws IllegalArgumentException if playerId is null
     */
    default CompletableFuture<Collection<DirtyDataEntry>> getDirtyEntriesAsync(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> getDirtyEntries(playerId));
    }
    
    /**
     * Asynchronously clears the dirty flag for the specified player and schema.
     * 
     * @param playerId The ID of the player
     * @param schemaKey The schema key
     * @return A CompletableFuture that completes when the operation is done
     * @throws IllegalArgumentException if playerId or schemaKey is null
     */
    default CompletableFuture<Void> clearDirtyAsync(UUID playerId, String schemaKey) {
        return CompletableFuture.runAsync(() -> clearDirty(playerId, schemaKey));
    }
    
    /**
     * Checks if the cache supports persistence.
     * 
     * @return true if the cache supports persistence, false otherwise
     */
    default boolean supportsPersistence() {
        return false;
    }
    
    /**
     * Performs cleanup operations on the cache.
     * This method should be called periodically to remove expired entries
     * or perform other maintenance tasks.
     */
    default void cleanup() {
        // Default implementation does nothing
    }
}