package sh.harold.fulcrum.api.maintenance;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Logical maintenance scopes that can be toggled by the registry.
 */
public enum MaintenanceScope {
    NETWORK("network");

    private final String key;

    MaintenanceScope(String key) {
        this.key = key;
    }

    /**
     * Resolves the scope from its serialized key.
     */
    public static Optional<MaintenanceScope> fromKey(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (MaintenanceScope scope : values()) {
            if (Objects.equals(scope.key, normalized)) {
                return Optional.of(scope);
            }
        }
        return Optional.empty();
    }

    /**
     * @return canonical identifier used in Redis/message payloads.
     */
    public String key() {
        return key;
    }
}
