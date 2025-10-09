package sh.harold.fulcrum.api.world;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents world data stored in the database.
 * Contains all metadata and configuration for a world.
 */
public class WorldData {
    
    private UUID id;
    private String serverId;
    private String worldName;
    private String displayName;
    private String mapType;
    private JsonObject mapData;
    private JsonObject pois;
    private Instant createdAt;
    private Instant updatedAt;
    
    // Cached Bukkit world reference (transient - not stored in DB)
    private transient org.bukkit.World bukkitWorld;
    
    public WorldData() {
        this.mapData = new JsonObject();
        this.pois = new JsonObject();
    }
    
    public WorldData(String serverId, String worldName) {
        this();
        this.serverId = serverId;
        this.worldName = worldName;
    }
    
    // Builder pattern for fluent API
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private final WorldData data = new WorldData();
        
        public Builder id(UUID id) {
            data.id = id;
            return this;
        }
        
        public Builder serverId(String serverId) {
            data.serverId = serverId;
            return this;
        }
        
        public Builder worldName(String worldName) {
            data.worldName = worldName;
            return this;
        }
        
        public Builder displayName(String displayName) {
            data.displayName = displayName;
            return this;
        }
        
        public Builder mapType(String mapType) {
            data.mapType = mapType;
            return this;
        }
        
        public Builder mapData(JsonObject mapData) {
            data.mapData = mapData;
            return this;
        }
        
        public Builder pois(JsonObject pois) {
            data.pois = pois;
            return this;
        }
        
        public Builder createdAt(Instant createdAt) {
            data.createdAt = createdAt;
            return this;
        }
        
        public Builder updatedAt(Instant updatedAt) {
            data.updatedAt = updatedAt;
            return this;
        }
        
        public WorldData build() {
            if (data.serverId == null || data.worldName == null) {
                throw new IllegalStateException("serverId and worldName are required");
            }
            if (data.id == null) {
                data.id = UUID.randomUUID();
            }
            return data;
        }
    }
    
    // Getters and setters
    public UUID getId() {
        return id;
    }
    
    public void setId(UUID id) {
        this.id = id;
    }
    
    public String getServerId() {
        return serverId;
    }
    
    public void setServerId(String serverId) {
        this.serverId = serverId;
    }
    
    public String getWorldName() {
        return worldName;
    }
    
    public void setWorldName(String worldName) {
        this.worldName = worldName;
    }
    
    public String getDisplayName() {
        return displayName != null ? displayName : worldName;
    }
    
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
    
    public String getMapType() {
        return mapType;
    }
    
    public void setMapType(String mapType) {
        this.mapType = mapType;
    }
    
    public JsonObject getMapData() {
        return mapData;
    }
    
    public void setMapData(JsonObject mapData) {
        this.mapData = mapData;
    }
    
    public JsonObject getPois() {
        return pois;
    }
    
    public void setPois(JsonObject pois) {
        this.pois = pois;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
    
    public Instant getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
    
    public org.bukkit.World getBukkitWorld() {
        return bukkitWorld;
    }
    
    public void setBukkitWorld(org.bukkit.World bukkitWorld) {
        this.bukkitWorld = bukkitWorld;
    }
    
    /**
     * Add or update a POI in this world.
     * 
     * @param identifier the POI identifier
     * @param poiData the POI data
     */
    public void addPOI(String identifier, JsonObject poiData) {
        if (pois == null) {
            pois = new JsonObject();
        }
        pois.add(identifier, poiData);
    }
    
    /**
     * Get a POI by identifier.
     * 
     * @param identifier the POI identifier
     * @return the POI data if it exists, null otherwise
     */
    public JsonObject getPOI(String identifier) {
        if (pois == null || !pois.has(identifier)) {
            return null;
        }
        return pois.getAsJsonObject(identifier);
    }
    
    /**
     * Remove a POI by identifier.
     * 
     * @param identifier the POI identifier
     * @return true if the POI was removed, false if it didn't exist
     */
    public boolean removePOI(String identifier) {
        if (pois == null || !pois.has(identifier)) {
            return false;
        }
        pois.remove(identifier);
        return true;
    }
    
    /**
     * Add or update metadata in the map data.
     * 
     * @param key the metadata key
     * @param value the metadata value
     */
    public void addMetadata(String key, Object value) {
        if (mapData == null) {
            mapData = new JsonObject();
        }
        if (value instanceof String) {
            mapData.addProperty(key, (String) value);
        } else if (value instanceof Number) {
            mapData.addProperty(key, (Number) value);
        } else if (value instanceof Boolean) {
            mapData.addProperty(key, (Boolean) value);
        } else if (value instanceof JsonObject) {
            mapData.add(key, (JsonObject) value);
        }
    }
    
    /**
     * Get metadata from the map data.
     * 
     * @param key the metadata key
     * @return the metadata value if it exists, null otherwise
     */
    public Object getMetadata(String key) {
        if (mapData == null || !mapData.has(key)) {
            return null;
        }
        return mapData.get(key);
    }
}