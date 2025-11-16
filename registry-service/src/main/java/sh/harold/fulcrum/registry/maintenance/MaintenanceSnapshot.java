package sh.harold.fulcrum.registry.maintenance;

import sh.harold.fulcrum.api.maintenance.MaintenanceContext;
import sh.harold.fulcrum.api.maintenance.MaintenanceScope;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable view of all maintenance scopes tracked by the registry.
 */
public record MaintenanceSnapshot(Map<MaintenanceScope, MaintenanceContext> contexts) {

    public MaintenanceSnapshot {
        contexts = Map.copyOf(Objects.requireNonNull(contexts, "contexts"));
    }

    public Optional<MaintenanceContext> get(MaintenanceScope scope) {
        return Optional.ofNullable(contexts.get(scope));
    }

    public Collection<MaintenanceContext> all() {
        return contexts.values();
    }

    public boolean isEmpty() {
        return contexts.isEmpty();
    }
}
