package sh.harold.fulcrum.api.messagebus.messages;

import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageType;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Broadcast by the registry to instruct services to begin evacuation.
 */
@MessageType(value = ChannelConstants.REGISTRY_SHUTDOWN_INTENT, version = 1)
public final class ShutdownIntentMessage implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;

    private String id;
    private List<String> services = new ArrayList<>();
    private int countdownSeconds;
    private String reason;
    private String backendTransferHint = "lobby";
    private boolean force;
    private boolean cancelled;
    private long createdAt = Instant.now().toEpochMilli();

    public ShutdownIntentMessage() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public List<String> getServices() {
        return Collections.unmodifiableList(services);
    }

    public void setServices(List<String> services) {
        this.services = services == null ? new ArrayList<>() : new ArrayList<>(services);
    }

    public int getCountdownSeconds() {
        return countdownSeconds;
    }

    public void setCountdownSeconds(int countdownSeconds) {
        this.countdownSeconds = countdownSeconds;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getBackendTransferHint() {
        return backendTransferHint;
    }

    public void setBackendTransferHint(String backendTransferHint) {
        this.backendTransferHint = backendTransferHint;
    }

    public boolean isForce() {
        return force;
    }

    public void setForce(boolean force) {
        this.force = force;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public void validate() {
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("Shutdown intent id is required");
        }
        if (services == null || services.isEmpty()) {
            throw new IllegalStateException("Shutdown intent requires at least one service");
        }
        if (!cancelled && countdownSeconds <= 0) {
            throw new IllegalStateException("Shutdown intent countdown must be positive");
        }
        services.replaceAll(service -> Objects.requireNonNull(service, "serviceId cannot be null"));
        if (backendTransferHint == null || backendTransferHint.isBlank()) {
            backendTransferHint = "lobby";
        }
        if (createdAt <= 0) {
            createdAt = Instant.now().toEpochMilli();
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public String toString() {
        return "ShutdownIntentMessage{" +
                "id='" + id + '\'' +
                ", services=" + services +
                ", countdownSeconds=" + countdownSeconds +
                ", reason='" + reason + '\'' +
                ", backendTransferHint='" + backendTransferHint + '\'' +
                ", force=" + force +
                ", cancelled=" + cancelled +
                ", createdAt=" + createdAt +
                '}';
    }
}
