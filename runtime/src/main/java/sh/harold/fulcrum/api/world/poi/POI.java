package sh.harold.fulcrum.api.world.poi;

import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.World;
import java.util.UUID;

/**
 * Represents a Point of Interest in a world.
 * POIs are special locations or areas that can trigger events or behaviors.
 */
public class POI {
    
    private final UUID id;
    private final String identifier;
    private final String type;
    private final Location location;
    private final JsonObject data;
    private boolean active;
    
    public POI(String identifier, String type, Location location) {
        this.id = UUID.randomUUID();
        this.identifier = identifier;
        this.type = type;
        this.location = location;
        this.data = new JsonObject();
        this.active = true;
    }
    
    public POI(String identifier, String type, Location location, JsonObject data) {
        this.id = UUID.randomUUID();
        this.identifier = identifier;
        this.type = type;
        this.location = location;
        this.data = data != null ? data : new JsonObject();
        this.active = true;
    }
    
    /**
     * Create a POI from JSON data.
     * 
     * @param identifier the POI identifier
     * @param world the world this POI belongs to
     * @param jsonData the JSON data containing POI information
     * @return a new POI instance
     */
    public static POI fromJson(String identifier, World world, JsonObject jsonData) {
        String type = jsonData.has("type") ? jsonData.get("type").getAsString() : "default";
        
        double x = jsonData.has("x") ? jsonData.get("x").getAsDouble() : 0;
        double y = jsonData.has("y") ? jsonData.get("y").getAsDouble() : 64;
        double z = jsonData.has("z") ? jsonData.get("z").getAsDouble() : 0;
        float yaw = jsonData.has("yaw") ? jsonData.get("yaw").getAsFloat() : 0;
        float pitch = jsonData.has("pitch") ? jsonData.get("pitch").getAsFloat() : 0;
        
        Location location = new Location(world, x, y, z, yaw, pitch);
        
        JsonObject data = jsonData.has("data") ? jsonData.getAsJsonObject("data") : new JsonObject();
        
        POI poi = new POI(identifier, type, location, data);
        
        if (jsonData.has("active")) {
            poi.setActive(jsonData.get("active").getAsBoolean());
        }
        
        return poi;
    }
    
    /**
     * Convert this POI to JSON format for storage.
     * 
     * @return JSON representation of this POI
     */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        json.addProperty("x", location.getX());
        json.addProperty("y", location.getY());
        json.addProperty("z", location.getZ());
        json.addProperty("yaw", location.getYaw());
        json.addProperty("pitch", location.getPitch());
        json.addProperty("active", active);
        
        if (data != null && data.size() > 0) {
            json.add("data", data);
        }
        
        return json;
    }
    
    /**
     * Check if a location is within range of this POI.
     * 
     * @param loc the location to check
     * @param range the range in blocks
     * @return true if the location is within range
     */
    public boolean isInRange(Location loc, double range) {
        if (loc.getWorld() != location.getWorld()) {
            return false;
        }
        return location.distance(loc) <= range;
    }
    
    /**
     * Get custom data value from this POI.
     * 
     * @param key the data key
     * @return the value if it exists, null otherwise
     */
    public Object getData(String key) {
        if (data == null || !data.has(key)) {
            return null;
        }
        return data.get(key);
    }
    
    /**
     * Set custom data value for this POI.
     * 
     * @param key the data key
     * @param value the value to set
     */
    public void setData(String key, Object value) {
        if (value instanceof String) {
            data.addProperty(key, (String) value);
        } else if (value instanceof Number) {
            data.addProperty(key, (Number) value);
        } else if (value instanceof Boolean) {
            data.addProperty(key, (Boolean) value);
        } else if (value instanceof JsonObject) {
            data.add(key, (JsonObject) value);
        }
    }
    
    // Getters and setters
    public UUID getId() {
        return id;
    }
    
    public String getIdentifier() {
        return identifier;
    }
    
    public String getType() {
        return type;
    }
    
    public Location getLocation() {
        return location;
    }
    
    public JsonObject getData() {
        return data;
    }
    
    public boolean isActive() {
        return active;
    }
    
    public void setActive(boolean active) {
        this.active = active;
    }
    
    public World getWorld() {
        return location.getWorld();
    }
    
    @Override
    public String toString() {
        return String.format("POI{id=%s, identifier='%s', type='%s', location=%s, active=%s}",
            id, identifier, type, location, active);
    }
}