package sh.harold.fulcrum.api.environment.directory;

import java.util.Map;
import java.util.Optional;

/**
 * Facade for accessing registry-managed environment definitions.
 */
public interface EnvironmentDirectoryService {

    /**
     * @return the current cached snapshot of the environment directory.
     */
    EnvironmentDirectoryView getDirectory();

    /**
     * Convenience accessor for retrieving a descriptor by environment id.
     */
    default Optional<EnvironmentDescriptorView> getEnvironment(String id) {
        return getDirectory().get(id);
    }

    /**
     * Returns a derived map of environment ids to their module sets for compatibility with legacy consumers.
     */
    default Map<String, EnvironmentDescriptorView> asMap() {
        return getDirectory().environments();
    }

    /**
     * Forces a refresh from the backing store (Redis/registry).
     */
    void refresh();
}
