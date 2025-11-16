package sh.harold.fulcrum.velocity.maintenance;

import sh.harold.fulcrum.api.maintenance.MaintenanceContext;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

final class VelocityMaintenanceStateService implements MaintenanceStateService {
    private final AtomicReference<MaintenanceContext> networkContext = new AtomicReference<>();

    @Override
    public Optional<MaintenanceContext> getNetworkContext() {
        return Optional.ofNullable(networkContext.get());
    }

    @Override
    public void setNetworkContext(MaintenanceContext context) {
        Objects.requireNonNull(context, "context");
        networkContext.set(context);
    }

    @Override
    public boolean isNetworkMaintenanceActive() {
        MaintenanceContext context = networkContext.get();
        return context != null && context.isActive();
    }

    @Override
    public void clearNetworkContext(UUID contextId) {
        if (contextId == null) {
            networkContext.set(null);
            return;
        }
        networkContext.updateAndGet(existing -> existing != null && contextId.equals(existing.id()) ? null : existing);
    }

    @Override
    public void clearAll() {
        networkContext.set(null);
    }
}
