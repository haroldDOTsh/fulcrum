package sh.harold.fulcrum.api.environment.directory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * Immutable view of a single environment entry published by the registry.
 */
public final class EnvironmentDescriptorView implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String id;
    private final String tag;
    private final List<String> modules;
    private final String description;

    @JsonCreator
    public EnvironmentDescriptorView(@JsonProperty("id") String id,
                                     @JsonProperty("tag") String tag,
                                     @JsonProperty("modules") List<String> modules,
                                     @JsonProperty("description") String description) {
        this.id = Objects.requireNonNull(id, "id");
        this.tag = tag != null ? tag : id;
        this.modules = List.copyOf(modules != null ? modules : List.of());
        this.description = description != null ? description : "";
    }

    public String id() {
        return id;
    }

    public String tag() {
        return tag;
    }

    public List<String> modules() {
        return modules;
    }

    public String description() {
        return description;
    }
}
