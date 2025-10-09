package sh.harold.fulcrum.api.world.poi;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import com.google.gson.JsonObject;

/**
 * Base interface for Points of Interest handlers.
 * Implementations handle specific types of POIs within worlds.
 */
public interface POIHandler {
    
    /**
     * Get the unique identifier for this POI type
     */
    String getIdentifier();
    
    /**
     * Get the priority of this handler (higher = more important)
     */
    int getPriority();
    
    /**
     * Handle a player interacting with this POI
     */
    void handleInteraction(Player player, Location location, JsonObject config);
    
    /**
     * Check if this handler can process the given POI configuration
     */
    boolean canHandle(JsonObject config);
    
    /**
     * Validate a POI configuration
     */
    boolean validateConfiguration(JsonObject config);
    
    /**
     * Get the default configuration for this POI type
     */
    JsonObject getDefaultConfiguration();
}