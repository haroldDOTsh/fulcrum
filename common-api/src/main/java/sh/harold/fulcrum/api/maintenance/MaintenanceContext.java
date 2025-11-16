package sh.harold.fulcrum.api.maintenance;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable snapshot of a maintenance context distributed via Redis and the message bus.
 */
public record MaintenanceContext(
        UUID id,
        MaintenanceScope scope,
        MaintenanceStatus status,
        Instant updatedAt,
        UUID actor,
        Instant expiresAt
) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public MaintenanceContext {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public String shortId() {
        String text = id.toString();
        return text.length() <= 8 ? text : text.substring(0, 8);
    }

    public boolean isActive() {
        return status == MaintenanceStatus.ON;
    }
}
