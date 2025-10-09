package sh.harold.fulcrum.fundamentals.world.model;

import com.google.gson.JsonObject;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Represents a schematic-backed world definition hydrated from PostgreSQL.
 */
public class LoadedWorld {
    private final UUID id;
    private final String worldName;
    private final String displayName;
    private final JsonObject metadata;
    private final File schematicFile;
    private final List<PoiDefinition> pois;
    private final Instant updatedAt;

    public LoadedWorld(UUID id,
                       String worldName,
                       String displayName,
                       JsonObject metadata,
                       File schematicFile,
                       List<PoiDefinition> pois,
                       Instant updatedAt) {
        this.id = id;
        this.worldName = worldName;
        this.displayName = displayName != null && !displayName.isBlank() ? displayName : worldName;
        this.metadata = metadata != null ? metadata : new JsonObject();
        this.schematicFile = schematicFile;
        this.pois = pois != null ? List.copyOf(pois) : List.of();
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getWorldName() {
        return worldName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public JsonObject getMetadata() {
        return metadata;
    }

    public String getMapId() {
        return metadata != null && metadata.has("mapId") ? metadata.get("mapId").getAsString() : id.toString();
    }

    public String getGameId() {
        return metadata != null && metadata.has("gameId") ? metadata.get("gameId").getAsString() : "unknown";
    }

    public String getAuthor() {
        return metadata != null && metadata.has("author") ? metadata.get("author").getAsString() : "unknown";
    }

    public File getSchematicFile() {
        return schematicFile;
    }

    public List<PoiDefinition> getPois() {
        return pois;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
