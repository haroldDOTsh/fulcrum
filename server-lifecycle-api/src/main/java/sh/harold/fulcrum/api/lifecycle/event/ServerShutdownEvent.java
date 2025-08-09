package sh.harold.fulcrum.api.lifecycle.event;

import sh.harold.fulcrum.api.lifecycle.ServerMetadata;

import java.util.UUID;

/**
 * Event fired when a server begins shutdown.
 */
public class ServerShutdownEvent extends ServerLifecycleEvent {
    private final String reason;
    private final UUID requestedBy;
    private final boolean restart;

    public ServerShutdownEvent(ServerMetadata server, String reason, UUID requestedBy, boolean restart) {
        super(server);
        this.reason = reason;
        this.requestedBy = requestedBy;
        this.restart = restart;
    }

    public String getReason() {
        return reason;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public boolean isRestart() {
        return restart;
    }
}