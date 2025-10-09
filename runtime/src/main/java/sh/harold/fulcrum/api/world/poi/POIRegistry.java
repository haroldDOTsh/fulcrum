package sh.harold.fulcrum.api.world.poi;

import org.bukkit.Location;
import org.bukkit.World;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Registry for Points of Interest handlers.
 * Allows plugins to register their own POI handlers and provides
 * a clear API for custom POI implementation.
 */
public class POIRegistry {
    
    private final Map<String, POIHandler> handlers = new ConcurrentHashMap<>();
    private final Map<String, Map<Location, JsonObject>> worldPOIs = new ConcurrentHashMap<>();
    private final Logger logger;
    
    public POIRegistry(Logger logger) {
        this.logger = logger;
    }
    
    /**
     * Register a POI handler with the system.
     * Plugins can use this to register their own POI handlers.
     * 
     * @param handler The POI handler to register
     * @return true if registered successfully, false if identifier already exists
     */
    public boolean registerHandler(POIHandler handler) {
        String identifier = handler.getIdentifier();
        if (handlers.containsKey(identifier)) {
            logger.warning("POI handler with identifier '" + identifier + "' already registered");
            return false;
        }
        
        handlers.put(identifier, handler);
        logger.info("Registered POI handler: " + identifier);
        return true;
    }
    
    /**
     * Unregister a POI handler.
     * 
     * @param identifier The identifier of the handler to unregister
     * @return true if unregistered, false if not found
     */
    public boolean unregisterHandler(String identifier) {
        POIHandler removed = handlers.remove(identifier);
        if (removed != null) {
            logger.info("Unregistered POI handler: " + identifier);
            return true;
        }
        return false;
    }
    
    /**
     * Get a POI handler by its identifier.
     * 
     * @param identifier The handler identifier
     * @return The handler or null if not found
     */
    public POIHandler getHandler(String identifier) {
        return handlers.get(identifier);
    }
    
    /**
     * Get all registered handlers.
     * 
     * @return Unmodifiable map of all handlers
     */
    public Map<String, POIHandler> getAllHandlers() {
        return Collections.unmodifiableMap(handlers);
    }
    
    /**
     * Register a POI in a world.
     * 
     * @param world The world
     * @param location The POI location
     * @param configuration The POI configuration
     */
    public void registerPOI(World world, Location location, JsonObject configuration) {
        String worldName = world.getName();
        worldPOIs.computeIfAbsent(worldName, k -> new ConcurrentHashMap<>())
                .put(location, configuration);
        
        logger.fine("Registered POI at " + location + " in world " + worldName);
    }
    
    /**
     * Get all POIs in a world.
     * 
     * @param worldName The world name
     * @return Map of POI locations to their configurations
     */
    public Map<Location, JsonObject> getWorldPOIs(String worldName) {
        return worldPOIs.getOrDefault(worldName, Collections.emptyMap());
    }
    
    /**
     * Remove a POI from a world.
     * 
     * @param world The world
     * @param location The POI location
     * @return true if removed, false if not found
     */
    public boolean removePOI(World world, Location location) {
        Map<Location, JsonObject> pois = worldPOIs.get(world.getName());
        if (pois != null) {
            JsonObject removed = pois.remove(location);
            if (removed != null) {
                logger.fine("Removed POI at " + location + " from world " + world.getName());
                return true;
            }
        }
        return false;
    }
    
    /**
     * Clear all POIs in a world.
     * 
     * @param worldName The world name
     */
    public void clearWorldPOIs(String worldName) {
        Map<Location, JsonObject> removed = worldPOIs.remove(worldName);
        if (removed != null && !removed.isEmpty()) {
            logger.info("Cleared " + removed.size() + " POIs from world " + worldName);
        }
    }
    
    /**
     * Find the nearest POI of a specific type to a location.
     * 
     * @param location The reference location
     * @param type The POI type identifier
     * @param maxDistance Maximum distance to search
     * @return The nearest POI location and config, or null if none found
     */
    public Map.Entry<Location, JsonObject> findNearestPOI(Location location, String type, double maxDistance) {
        String worldName = location.getWorld().getName();
        Map<Location, JsonObject> pois = worldPOIs.get(worldName);
        
        if (pois == null || pois.isEmpty()) {
            return null;
        }
        
        Map.Entry<Location, JsonObject> nearest = null;
        double nearestDistance = maxDistance;
        
        for (Map.Entry<Location, JsonObject> entry : pois.entrySet()) {
            JsonObject config = entry.getValue();
            if (config.has("type") && type.equals(config.get("type").getAsString())) {
                double distance = location.distance(entry.getKey());
                if (distance < nearestDistance) {
                    nearest = entry;
                    nearestDistance = distance;
                }
            }
        }
        
        return nearest;
    }
}