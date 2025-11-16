package sh.harold.fulcrum.api.messagebus.messages.network;

import sh.harold.fulcrum.api.maintenance.MaintenanceScope;
import sh.harold.fulcrum.api.maintenance.MaintenanceStatus;
import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Broadcast whenever a maintenance context transitions between ON/OFF states.
 */
public final class MaintenanceToggleMessage implements BaseMessage, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private UUID contextId;
    private MaintenanceScope scope;
    private MaintenanceStatus status;
    private Instant updatedAt;
    private Instant expiresAt;
    private UUID actor;

    public MaintenanceToggleMessage() {
        // Jackson
    }

    public MaintenanceToggleMessage(UUID contextId,
                                    MaintenanceScope scope,
                                    MaintenanceStatus status,
                                    Instant updatedAt,
                                    Instant expiresAt,
                                    UUID actor) {
        this.contextId = Objects.requireNonNull(contextId, "contextId");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.status = Objects.requireNonNull(status, "status");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.expiresAt = expiresAt;
        this.actor = actor;
    }

    @Override
    public String getMessageType() {
        return ChannelConstants.REGISTRY_MAINTENANCE_UPDATE;
    }

    public UUID getContextId() {
        return contextId;
    }

    public void setContextId(UUID contextId) {
        this.contextId = contextId;
    }

    public MaintenanceScope getScope() {
        return scope;
    }

    public void setScope(MaintenanceScope scope) {
        this.scope = scope;
    }

    public MaintenanceStatus getStatus() {
        return status;
    }

    public void setStatus(MaintenanceStatus status) {
        this.status = status;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public UUID getActor() {
        return actor;
    }

    public void setActor(UUID actor) {
        this.actor = actor;
    }
}
