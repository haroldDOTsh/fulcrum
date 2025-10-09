package sh.harold.fulcrum.api.world;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Service interface for generic world management.
 * Provides methods for loading, unloading, and managing worlds from the database.
 */
public interface WorldService {
    
    /**
     * Load a world from the database by name.
     * 
     * @param worldName the name of the world to load
     * @return a future containing the loaded world data if found
     */
    CompletableFuture<Optional<WorldData>> loadWorld(String worldName);
    
    /**
     * Load a world from the database by ID.
     * 
     * @param worldId the ID of the world to load
     * @return a future containing the loaded world data if found
     */
    CompletableFuture<Optional<WorldData>> loadWorld(UUID worldId);
    
    /**
     * Unload a world from memory.
     * 
     * @param worldName the name of the world to unload
     * @return a future indicating if the unload was successful
     */
    CompletableFuture<Boolean> unloadWorld(String worldName);
    
    /**
     * Reset a world to its initial state.
     * 
     * @param worldName the name of the world to reset
     * @return a future indicating if the reset was successful
     */
    CompletableFuture<Boolean> resetWorld(String worldName);
    
    /**
     * Get all currently loaded worlds.
     * 
     * @return list of loaded world data
     */
    List<WorldData> getLoadedWorlds();
    
    /**
     * Get all worlds for a specific server from the database.
     * 
     * @param serverId the server ID to query worlds for
     * @return a future containing the list of world data
     */
    CompletableFuture<List<WorldData>> getWorldsForServer(String serverId);
    
    /**
     * Get all worlds of a specific type from the database.
     * 
     * @param mapType the type of map (e.g., "minigame", "lobby", "skyblock")
     * @return a future containing the list of world data
     */
    CompletableFuture<List<WorldData>> getWorldsByType(String mapType);
    
    /**
     * Save or update world data in the database.
     * 
     * @param worldData the world data to save
     * @return a future containing the saved world data
     */
    CompletableFuture<WorldData> saveWorld(WorldData worldData);
    
    /**
     * Delete a world from the database.
     * 
     * @param worldId the ID of the world to delete
     * @return a future indicating if the deletion was successful
     */
    CompletableFuture<Boolean> deleteWorld(UUID worldId);
    
    /**
     * Check if a world is currently loaded.
     * 
     * @param worldName the name of the world to check
     * @return true if the world is loaded, false otherwise
     */
    boolean isWorldLoaded(String worldName);
    
    /**
     * Get the world manager instance.
     * 
     * @return the world manager
     */
    WorldManager getWorldManager();
}