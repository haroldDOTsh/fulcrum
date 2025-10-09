package sh.harold.fulcrum.api.world.poi.handlers;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import com.google.gson.JsonObject;
import sh.harold.fulcrum.api.world.poi.POIHandler;
import java.util.logging.Logger;

/**
 * Hard-coded implementation for global spawn points.
 * Configuration in JSONB: {"priority": 1, "yaw": 0.0, "pitch": 0.0}
 * Handles player respawning and initial spawn.
 */
public class SpawnpointHandler implements POIHandler, Listener {
    
    private static final String IDENTIFIER = "global_spawnpoint";
    private final Logger logger;
    
    public SpawnpointHandler(Logger logger) {
        this.logger = logger;
    }
    
    @Override
    public String getIdentifier() {
        return IDENTIFIER;
    }
    
    @Override
    public int getPriority() {
        return 1; // Default priority, can be overridden by configuration
    }
    
    @Override
    public void handleInteraction(Player player, Location location, JsonObject config) {
        // Apply yaw and pitch from configuration
        if (config.has("yaw")) {
            location.setYaw(config.get("yaw").getAsFloat());
        }
        if (config.has("pitch")) {
            location.setPitch(config.get("pitch").getAsFloat());
        }
        
        // Teleport the player to the spawn point
        player.teleport(location);
    }
    
    @Override
    public boolean canHandle(JsonObject config) {
        // This handler specifically handles global_spawnpoint type POIs
        return config.has("type") && 
               IDENTIFIER.equals(config.get("type").getAsString());
    }
    
    @Override
    public boolean validateConfiguration(JsonObject config) {
        // Validate required and optional fields
        if (!config.has("priority")) {
            return false;
        }
        
        // Check optional fields have correct types if present
        if (config.has("yaw")) {
            try {
                config.get("yaw").getAsFloat();
            } catch (Exception e) {
                return false;
            }
        }
        
        if (config.has("pitch")) {
            try {
                config.get("pitch").getAsFloat();
            } catch (Exception e) {
                return false;
            }
        }
        
        return true;
    }
    
    @Override
    public JsonObject getDefaultConfiguration() {
        JsonObject config = new JsonObject();
        config.addProperty("type", IDENTIFIER);
        config.addProperty("priority", 1);
        config.addProperty("yaw", 0.0f);
        config.addProperty("pitch", 0.0f);
        return config;
    }
    
    /**
     * Handle player first join - teleport to global spawn
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPlayedBefore()) {
            // Find and teleport to global spawn point
            Location spawn = findGlobalSpawn(player);
            if (spawn != null) {
                JsonObject config = getDefaultConfiguration();
                handleInteraction(player, spawn, config);
                logger.info("Teleported new player " + player.getName() + " to global spawn");
            }
        }
    }
    
    /**
     * Handle player respawn - set respawn location to global spawn
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (!event.isBedSpawn() && !event.isAnchorSpawn()) {
            Location spawn = findGlobalSpawn(event.getPlayer());
            if (spawn != null) {
                JsonObject config = getDefaultConfiguration();
                // Apply configuration to the spawn location
                if (config.has("yaw")) {
                    spawn.setYaw(config.get("yaw").getAsFloat());
                }
                if (config.has("pitch")) {
                    spawn.setPitch(config.get("pitch").getAsFloat());
                }
                event.setRespawnLocation(spawn);
                logger.fine("Set respawn location for " + event.getPlayer().getName() + " to global spawn");
            }
        }
    }
    
    /**
     * Find the global spawn location for a player.
     * This will be extended to check POI registry in the world.
     */
    private Location findGlobalSpawn(Player player) {
        // For now, return world spawn point
        // This will be enhanced to check POI registry
        return player.getWorld().getSpawnLocation();
    }
}