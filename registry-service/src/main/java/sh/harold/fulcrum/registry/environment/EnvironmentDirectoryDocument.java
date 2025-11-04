package sh.harold.fulcrum.registry.environment;

import java.util.*;

public record EnvironmentDirectoryDocument(
        String id,
        String tag,
        List<String> modules,
        String description,
        int minPlayers,
        int maxPlayers,
        double playerFactor,
        Map<String, Object> settings
) {

    public EnvironmentDirectoryDocument {
        Objects.requireNonNull(id, "id");
        tag = tag != null && !tag.isBlank() ? tag : id;
        modules = List.copyOf(modules != null ? modules : List.of());
        description = description != null ? description : "";
        if (minPlayers < 0) {
            minPlayers = 0;
        }
        if (maxPlayers < minPlayers) {
            maxPlayers = minPlayers;
        }
        if (Double.compare(playerFactor, 0D) <= 0) {
            playerFactor = 1.0D;
        }
        settings = settings != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(settings))
                : Map.of();
    }
}
