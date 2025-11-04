package sh.harold.fulcrum.api.environment.directory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.util.*;

/**
 * Immutable view of a single environment entry published by the registry.
 */
public final class EnvironmentDescriptorView implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String id;
    private final String tag;
    private final List<String> modules;
    private final String description;
    private final int minPlayers;
    private final int maxPlayers;
    private final double playerFactor;
    private final Map<String, Object> settings;

    @JsonCreator
    public EnvironmentDescriptorView(@JsonProperty("id") String id,
                                     @JsonProperty("tag") String tag,
                                     @JsonProperty("modules") List<String> modules,
                                     @JsonProperty("description") String description,
                                     @JsonProperty("minPlayers") Integer minPlayers,
                                     @JsonProperty("maxPlayers") Integer maxPlayers,
                                     @JsonProperty("playerFactor") Double playerFactor,
                                     @JsonProperty("settings") Map<String, Object> settings) {
        this.id = Objects.requireNonNull(id, "id");
        this.tag = tag != null ? tag : id;
        this.modules = List.copyOf(modules != null ? modules : List.of());
        this.description = description != null ? description : "";
        this.minPlayers = normalizeMin(minPlayers);
        this.maxPlayers = normalizeMax(this.minPlayers, maxPlayers);
        this.playerFactor = normalizeFactor(playerFactor);
        this.settings = settings != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(settings))
                : Map.of();
    }

    @JsonProperty("id")
    public String id() {
        return id;
    }

    @JsonProperty("tag")
    public String tag() {
        return tag;
    }

    @JsonProperty("modules")
    public List<String> modules() {
        return modules;
    }

    @JsonProperty("description")
    public String description() {
        return description;
    }

    @JsonProperty("minPlayers")
    public int minPlayers() {
        return minPlayers;
    }

    @JsonProperty("maxPlayers")
    public int maxPlayers() {
        return maxPlayers;
    }

    @JsonProperty("playerFactor")
    public double playerFactor() {
        return playerFactor;
    }

    @JsonProperty("settings")
    public Map<String, Object> settings() {
        return settings;
    }

    private int normalizeMin(Integer value) {
        if (value == null || value < 0) {
            return 0;
        }
        return value;
    }

    private int normalizeMax(int min, Integer value) {
        if (value == null) {
            return min;
        }
        return Math.max(min, value);
    }

    private double normalizeFactor(Double value) {
        if (value == null || value <= 0) {
            return 1.0D;
        }
        return value;
    }
}
