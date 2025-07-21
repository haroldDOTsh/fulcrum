package sh.harold.fulcrum;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Represents the runtime environment configuration loaded from environment.yml.
 * Provides module lists for each role.
 */
public record RuntimeEnvironment(Map<String, RuntimeProfile> runtimes) {
    public List<String> getModulesFor(String role) {
        var profile = runtimes.get(role);
        return profile != null ? profile.modules() : Collections.emptyList();
    }
}
