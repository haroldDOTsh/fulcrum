package sh.harold.fulcrum.fundamentals.world.model;

import com.google.gson.JsonObject;
import com.sk89q.worldedit.math.BlockVector3;

/**
 * Represents a POI marker extracted from a schematic.
 * Coordinates are stored relative to the schematic origin.
 */
public record PoiDefinition(String identifier, String type, BlockVector3 position, JsonObject metadata) {

    public PoiDefinition {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("POI type is required");
        }
    }

    public JsonObject toConfigJson() {
        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        if (identifier != null && !identifier.isBlank()) {
            json.addProperty("id", identifier);
        }
        if (metadata != null) {
            metadata.entrySet()
                    .forEach(entry -> json.add(entry.getKey(), entry.getValue().deepCopy()));
        }
        return json;
    }
}
