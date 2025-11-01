package sh.harold.fulcrum.api.environment.directory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Snapshot of the registry-managed environment directory.
 */
public record EnvironmentDirectoryView(Map<String, EnvironmentDescriptorView> environments,
                                       String revision) implements Serializable {
    private static final long serialVersionUID = 1L;

    @JsonCreator
    public EnvironmentDirectoryView(@JsonProperty("environments") Map<String, EnvironmentDescriptorView> environments,
                                    @JsonProperty("revision") String revision) {
        this.environments = environments != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(environments))
                : Map.of();
        this.revision = revision;
    }

    public Optional<EnvironmentDescriptorView> get(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(environments.get(id));
    }
}
