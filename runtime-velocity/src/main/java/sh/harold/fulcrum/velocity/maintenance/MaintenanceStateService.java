package sh.harold.fulcrum.velocity.maintenance;

import sh.harold.fulcrum.api.maintenance.MaintenanceContext;

import java.util.Optional;
import java.util.UUID;

/**
 * Exposes the currently active maintenance context to Velocity features.
 */
public interface MaintenanceStateService {

    Optional<MaintenanceContext> getNetworkContext();

    void setNetworkContext(MaintenanceContext context);

    boolean isNetworkMaintenanceActive();

    void clearNetworkContext(UUID contextId);

    void clearAll();
}
