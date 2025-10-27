package sh.harold.fulcrum.fundamentals.props.model;

import com.google.gson.JsonObject;
import sh.harold.fulcrum.fundamentals.world.model.PoiDefinition;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Represents a cached prop schematic and associated metadata.
 */
public final class PropDefinition {
    private final UUID id;
    private final String name;
    private final String displayName;
    private final String type;
    private final JsonObject metadata;
    private final File schematicFile;
    private final List<PoiDefinition> pois;
    private final Instant updatedAt;

    public PropDefinition(UUID id,
                          String name,
                          String displayName,
                          String type,
                          JsonObject metadata,
                          File schematicFile,
                          List<PoiDefinition> pois,
                          Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.displayName = displayName != null && !displayName.isBlank() ? displayName : name;
        this.type = type;
        this.metadata = metadata != null ? metadata : new JsonObject();
        this.schematicFile = schematicFile;
        this.pois = pois != null ? List.copyOf(pois) : List.of();
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getType() {
        return type;
    }

    public JsonObject getMetadata() {
        return metadata;
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
