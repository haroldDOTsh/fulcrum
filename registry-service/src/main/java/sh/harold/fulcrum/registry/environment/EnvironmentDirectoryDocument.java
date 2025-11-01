package sh.harold.fulcrum.registry.environment;

import java.util.List;
import java.util.Objects;

public record EnvironmentDirectoryDocument(
        String id,
        String tag,
        List<String> modules,
        String description
) {

    public EnvironmentDirectoryDocument {
        Objects.requireNonNull(id, "id");
        tag = tag != null && !tag.isBlank() ? tag : id;
        modules = List.copyOf(modules != null ? modules : List.of());
        description = description != null ? description : "";
    }
}
