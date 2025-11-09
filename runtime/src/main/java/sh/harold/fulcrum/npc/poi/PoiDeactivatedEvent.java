package sh.harold.fulcrum.npc.poi;

import com.google.gson.JsonObject;
import org.bukkit.Location;

import java.util.Objects;
import java.util.Optional;

/**
 * Fired when an existing POI is removed or a world unloads.
 */
public record PoiDeactivatedEvent(String worldName, Location location, JsonObject configuration) {
    public PoiDeactivatedEvent(String worldName, Location location, JsonObject configuration) {
        this.worldName = Objects.requireNonNull(worldName, "worldName");
        this.location = Objects.requireNonNull(location, "location").clone();
        this.configuration = Objects.requireNonNull(configuration, "configuration").deepCopy();
    }

    @Override
    public Location location() {
        return location.clone();
    }

    @Override
    public JsonObject configuration() {
        return configuration.deepCopy();
    }

    public Optional<String> anchorId() {
        return configuration.has("id")
                ? Optional.ofNullable(configuration.get("id").getAsString())
                : Optional.empty();
    }
}
